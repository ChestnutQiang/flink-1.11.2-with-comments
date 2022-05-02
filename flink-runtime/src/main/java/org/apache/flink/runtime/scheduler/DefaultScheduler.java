/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.runtime.scheduler;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.blob.BlobWriter;
import org.apache.flink.runtime.checkpoint.CheckpointRecoveryFactory;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.executiongraph.failover.flip1.ExecutionFailureHandler;
import org.apache.flink.runtime.executiongraph.failover.flip1.FailoverStrategy;
import org.apache.flink.runtime.executiongraph.failover.flip1.FailureHandlingResult;
import org.apache.flink.runtime.executiongraph.failover.flip1.RestartBackoffTimeStrategy;
import org.apache.flink.runtime.executiongraph.restart.ThrowingRestartStrategy;
import org.apache.flink.runtime.io.network.partition.JobMasterPartitionTracker;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.jobmaster.slotpool.ThrowingSlotProvider;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.operators.coordination.OperatorCoordinator;
import org.apache.flink.runtime.rest.handler.legacy.backpressure.BackPressureStatsTracker;
import org.apache.flink.runtime.scheduler.strategy.ExecutionVertexID;
import org.apache.flink.runtime.scheduler.strategy.SchedulingStrategy;
import org.apache.flink.runtime.scheduler.strategy.SchedulingStrategyFactory;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.taskmanager.TaskExecutionState;
import org.apache.flink.util.ExceptionUtils;

import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The future default scheduler.
 */
public class DefaultScheduler extends SchedulerBase implements SchedulerOperations {

	private final Logger log;

	private final ClassLoader userCodeLoader;

	private final ExecutionSlotAllocator executionSlotAllocator;

	private final ExecutionFailureHandler executionFailureHandler;

	private final ScheduledExecutor delayExecutor;

	private final SchedulingStrategy schedulingStrategy;

	private final ExecutionVertexOperations executionVertexOperations;

	private final Set<ExecutionVertexID> verticesWaitingForRestart;

	DefaultScheduler(final Logger log, final JobGraph jobGraph, final BackPressureStatsTracker backPressureStatsTracker, final Executor ioExecutor,
		final Configuration jobMasterConfiguration, final ScheduledExecutorService futureExecutor, final ScheduledExecutor delayExecutor,
		final ClassLoader userCodeLoader, final CheckpointRecoveryFactory checkpointRecoveryFactory, final Time rpcTimeout,
		final BlobWriter blobWriter, final JobManagerJobMetricGroup jobManagerJobMetricGroup, final ShuffleMaster<?> shuffleMaster,
		final JobMasterPartitionTracker partitionTracker, final SchedulingStrategyFactory schedulingStrategyFactory,
		final FailoverStrategy.Factory failoverStrategyFactory, final RestartBackoffTimeStrategy restartBackoffTimeStrategy,
		final ExecutionVertexOperations executionVertexOperations, final ExecutionVertexVersioner executionVertexVersioner,
		final ExecutionSlotAllocatorFactory executionSlotAllocatorFactory) throws Exception {

		/*************************************************
		 *
		 *  注释： 初始化 SchedulerBase
		 *  1、获取 ExecutioinGragh
		 *  2、获取 OperatorCoordinator
		 */
		super(log, jobGraph, backPressureStatsTracker, ioExecutor, jobMasterConfiguration, new ThrowingSlotProvider(),
			// this is not used any more in the new scheduler
			futureExecutor, userCodeLoader, checkpointRecoveryFactory, rpcTimeout,

			// TODO_MA 注释： 默认重启策略
			new ThrowingRestartStrategy.ThrowingRestartStrategyFactory(),

			blobWriter, jobManagerJobMetricGroup, Time.seconds(0), // this is not used any more in the new scheduler
			shuffleMaster, partitionTracker, executionVertexVersioner, false);

		this.log = log;

		this.delayExecutor = checkNotNull(delayExecutor);
		this.userCodeLoader = checkNotNull(userCodeLoader);
		this.executionVertexOperations = checkNotNull(executionVertexOperations);

		/*************************************************
		 *
		 *  注释： 获取 FailoverStrategy
		 */
		final FailoverStrategy failoverStrategy = failoverStrategyFactory.create(getSchedulingTopology(), getResultPartitionAvailabilityChecker());
		log.info("Using failover strategy {} for {} ({}).", failoverStrategy, jobGraph.getName(), jobGraph.getJobID());

		this.executionFailureHandler = new ExecutionFailureHandler(getSchedulingTopology(), failoverStrategy, restartBackoffTimeStrategy);

		/*************************************************
		 *
		 *  注释： 获取 SchedulingStrategyFactory
		 *  schedulingStrategy = EagerSchedulingStrategy
		 */
		this.schedulingStrategy = schedulingStrategyFactory.createInstance(this, getSchedulingTopology());
		this.executionSlotAllocator = checkNotNull(executionSlotAllocatorFactory).createInstance(getInputsLocationsRetriever());

		this.verticesWaitingForRestart = new HashSet<>();
	}

	// ------------------------------------------------------------------------
	// SchedulerNG
	// ------------------------------------------------------------------------

	@Override
	protected long getNumberOfRestarts() {
		return executionFailureHandler.getNumberOfRestarts();
	}

	@Override
	protected void startSchedulingInternal() {
		log.info("Starting scheduling with scheduling strategy [{}]", schedulingStrategy.getClass().getName());

		/*************************************************
		 *
		 *  注释： 更改 Job 的状态
		 */
		prepareExecutionGraphForNgScheduling();

		/*************************************************
		 *
		 *  注释： 调度
		 *  流式作业默认调度方式： schedulingStrategy = EagerSchedulingStrategy
		 *  1、EagerSchedulingStrategy（主要用于流式作业，所有顶点（ExecutionVertex）同时开始调度）
		 *  2、LazyFromSourcesSchedulingStrategy（主要用于批作业，从 Source 开始开始调度，其他顶点延迟调度）调度
		 */
		schedulingStrategy.startScheduling();
	}

	@Override
	protected void updateTaskExecutionStateInternal(final ExecutionVertexID executionVertexId, final TaskExecutionState taskExecutionState) {
		schedulingStrategy.onExecutionStateChange(executionVertexId, taskExecutionState.getExecutionState());
		maybeHandleTaskFailure(taskExecutionState, executionVertexId);
	}

	private void maybeHandleTaskFailure(final TaskExecutionState taskExecutionState, final ExecutionVertexID executionVertexId) {
		if(taskExecutionState.getExecutionState() == ExecutionState.FAILED) {
			final Throwable error = taskExecutionState.getError(userCodeLoader);
			handleTaskFailure(executionVertexId, error);
		}
	}

	private void handleTaskFailure(final ExecutionVertexID executionVertexId, @Nullable final Throwable error) {
		setGlobalFailureCause(error);
		notifyCoordinatorsAboutTaskFailure(executionVertexId, error);
		final FailureHandlingResult failureHandlingResult = executionFailureHandler.getFailureHandlingResult(executionVertexId, error);
		maybeRestartTasks(failureHandlingResult);
	}

	private void notifyCoordinatorsAboutTaskFailure(final ExecutionVertexID executionVertexId, @Nullable final Throwable error) {
		final ExecutionJobVertex jobVertex = getExecutionJobVertex(executionVertexId.getJobVertexId());
		final int subtaskIndex = executionVertexId.getSubtaskIndex();

		jobVertex.getOperatorCoordinators().forEach(c -> c.subtaskFailed(subtaskIndex, error));
	}

	@Override
	public void handleGlobalFailure(final Throwable error) {
		setGlobalFailureCause(error);

		log.info("Trying to recover from a global failure.", error);
		final FailureHandlingResult failureHandlingResult = executionFailureHandler.getGlobalFailureHandlingResult(error);
		maybeRestartTasks(failureHandlingResult);
	}

	private void maybeRestartTasks(final FailureHandlingResult failureHandlingResult) {
		if(failureHandlingResult.canRestart()) {
			restartTasksWithDelay(failureHandlingResult);
		} else {
			failJob(failureHandlingResult.getError());
		}
	}

	private void restartTasksWithDelay(final FailureHandlingResult failureHandlingResult) {
		final Set<ExecutionVertexID> verticesToRestart = failureHandlingResult.getVerticesToRestart();

		final Set<ExecutionVertexVersion> executionVertexVersions = new HashSet<>(
			executionVertexVersioner.recordVertexModifications(verticesToRestart).values());
		final boolean globalRecovery = failureHandlingResult.isGlobalFailure();

		addVerticesToRestartPending(verticesToRestart);

		final CompletableFuture<?> cancelFuture = cancelTasksAsync(verticesToRestart);

		delayExecutor.schedule(() -> FutureUtils
				.assertNoException(cancelFuture.thenRunAsync(restartTasks(executionVertexVersions, globalRecovery), getMainThreadExecutor())),
			failureHandlingResult.getRestartDelayMS(), TimeUnit.MILLISECONDS);
	}

	private void addVerticesToRestartPending(final Set<ExecutionVertexID> verticesToRestart) {
		verticesWaitingForRestart.addAll(verticesToRestart);
		transitionExecutionGraphState(JobStatus.RUNNING, JobStatus.RESTARTING);
	}

	private void removeVerticesFromRestartPending(final Set<ExecutionVertexID> verticesToRestart) {
		verticesWaitingForRestart.removeAll(verticesToRestart);
		if(verticesWaitingForRestart.isEmpty()) {
			transitionExecutionGraphState(JobStatus.RESTARTING, JobStatus.RUNNING);
		}
	}

	private Runnable restartTasks(final Set<ExecutionVertexVersion> executionVertexVersions, final boolean isGlobalRecovery) {
		return () -> {
			final Set<ExecutionVertexID> verticesToRestart = executionVertexVersioner.getUnmodifiedExecutionVertices(executionVertexVersions);

			removeVerticesFromRestartPending(verticesToRestart);

			resetForNewExecutions(verticesToRestart);

			try {
				restoreState(verticesToRestart, isGlobalRecovery);
			} catch(Throwable t) {
				handleGlobalFailure(t);
				return;
			}

			schedulingStrategy.restartTasks(verticesToRestart);
		};
	}

	private CompletableFuture<?> cancelTasksAsync(final Set<ExecutionVertexID> verticesToRestart) {
		final List<CompletableFuture<?>> cancelFutures = verticesToRestart.stream().map(this::cancelExecutionVertex).collect(Collectors.toList());

		return FutureUtils.combineAll(cancelFutures);
	}

	private CompletableFuture<?> cancelExecutionVertex(final ExecutionVertexID executionVertexId) {
		final ExecutionVertex vertex = getExecutionVertex(executionVertexId);

		notifyCoordinatorOfCancellation(vertex);

		executionSlotAllocator.cancel(executionVertexId);
		return executionVertexOperations.cancel(vertex);
	}

	@Override
	protected void scheduleOrUpdateConsumersInternal(final IntermediateResultPartitionID partitionId) {
		schedulingStrategy.onPartitionConsumable(partitionId);
	}

	// ------------------------------------------------------------------------
	// SchedulerOperations
	// ------------------------------------------------------------------------

	/*************************************************
	 *
	 *  注释： 在上一个步骤中：
	 *  1、ExecutionVertexID
	 *  2、ExecutioinGraph
	 *  3、根据以上两个条件找到 ExecutionVertex， 然后封装成 ExecutionVertexDeploymentOption
	 *  executionVertexDeploymentOptions.size() = Task Numbers
	 */
	@Override
	public void allocateSlotsAndDeploy(final List<ExecutionVertexDeploymentOption> executionVertexDeploymentOptions) {
		validateDeploymentOptions(executionVertexDeploymentOptions);

		// TODO_MA 注释： 各种映射处理
		final Map<ExecutionVertexID, ExecutionVertexDeploymentOption> deploymentOptionsByVertex = groupDeploymentOptionsByVertexId(
			executionVertexDeploymentOptions);

		final List<ExecutionVertexID> verticesToDeploy = executionVertexDeploymentOptions.stream()
			.map(ExecutionVertexDeploymentOption::getExecutionVertexId).collect(Collectors.toList());

		final Map<ExecutionVertexID, ExecutionVertexVersion> requiredVersionByVertex = executionVertexVersioner
			.recordVertexModifications(verticesToDeploy);

		// TODO_MA 注释： 更改 ExectionVertex 状态
		transitionToScheduled(verticesToDeploy);

		/*************************************************
		 *
		 *  注释： 申请Slot
		 *  参数： 待调度执行的 ExecutionVertex 集合
		 */
		final List<SlotExecutionVertexAssignment> slotExecutionVertexAssignments =
			allocateSlots(executionVertexDeploymentOptions);

		// TODO_MA 注释： 构建的 DeploymentHandle
		final List<DeploymentHandle> deploymentHandles = createDeploymentHandles(requiredVersionByVertex, deploymentOptionsByVertex,
			slotExecutionVertexAssignments);

		/*************************************************
		 *
		 *  注释： 部署运行
		 *  1、申请到了 slot
		 *  2、构件好了 Handler
		 *  3、执行部署
		 */
		waitForAllSlotsAndDeploy(deploymentHandles);
	}

	private void validateDeploymentOptions(final Collection<ExecutionVertexDeploymentOption> deploymentOptions) {
		deploymentOptions.stream().map(ExecutionVertexDeploymentOption::getExecutionVertexId).map(this::getExecutionVertex).forEach(
			v -> checkState(v.getExecutionState() == ExecutionState.CREATED, "expected vertex %s to be in CREATED state, was: %s", v.getID(),
				v.getExecutionState()));
	}

	private static Map<ExecutionVertexID, ExecutionVertexDeploymentOption> groupDeploymentOptionsByVertexId(
		final Collection<ExecutionVertexDeploymentOption> executionVertexDeploymentOptions) {
		return executionVertexDeploymentOptions.stream()
			.collect(Collectors.toMap(ExecutionVertexDeploymentOption::getExecutionVertexId, Function.identity()));
	}

	private List<SlotExecutionVertexAssignment> allocateSlots(final List<ExecutionVertexDeploymentOption> executionVertexDeploymentOptions) {

		/*************************************************
		 *
		 *  注释： 通过一个专业的 ExecutionSlotAllocator slot申请器 来申请 Slots
		 *  参数内部执行一下操作：
		 *  ExecutionVertexDeploymentOption ==> ExecutionVertexId ==>
		 *  ExecutionVertex ==> ExecutionVertexSchedulingRequirements
		 */
		return executionSlotAllocator.allocateSlotsFor(
			executionVertexDeploymentOptions.stream()
				.map(ExecutionVertexDeploymentOption::getExecutionVertexId)
				.map(this::getExecutionVertex)
				.map(ExecutionVertexSchedulingRequirementsMapper::from)
				.collect(Collectors.toList()));
	}

	private static List<DeploymentHandle> createDeploymentHandles(final Map<ExecutionVertexID, ExecutionVertexVersion> requiredVersionByVertex,
		final Map<ExecutionVertexID, ExecutionVertexDeploymentOption> deploymentOptionsByVertex,
		final List<SlotExecutionVertexAssignment> slotExecutionVertexAssignments) {

		/*************************************************
		 *
		 *  注释： 给每一个 ExecutionVertex 构建一个 Handler 用于部署：  DeploymentHandle
		 */
		return slotExecutionVertexAssignments.stream().map(slotExecutionVertexAssignment -> {
			final ExecutionVertexID executionVertexId = slotExecutionVertexAssignment.getExecutionVertexId();

			/*************************************************
			 *
			 *  注释： 一堆：DeploymentHandle
			 */
			return new DeploymentHandle(requiredVersionByVertex.get(executionVertexId), deploymentOptionsByVertex.get(executionVertexId),
				slotExecutionVertexAssignment);
		}).collect(Collectors.toList());
	}

	private void waitForAllSlotsAndDeploy(final List<DeploymentHandle> deploymentHandles) {

		/*************************************************
		 *
		 *  注释： 调用 deployAll() 部署任务
		 *  1、assignAllResources(deploymentHandles) 分配 slot
		 *  2、deployAll(deploymentHandles) 执行任务部署
		 *  之前只是申请！分配有可能成功，也有可能失败
		 *  -
		 *  把 deploymentHandles 中的每一个元素 和 slotExecutionVertexAssignments 中的每个元素做一一对应
		 */
		FutureUtils.assertNoException(assignAllResources(deploymentHandles).handle(deployAll(deploymentHandles)));
	}

	private CompletableFuture<Void> assignAllResources(final List<DeploymentHandle> deploymentHandles) {
		final List<CompletableFuture<Void>> slotAssignedFutures = new ArrayList<>();

		/*************************************************
		 *
		 *  注释： 每个 DeploymentHandle 去分配资源给自己对应的 ExecutionVertex
		 */
		for(DeploymentHandle deploymentHandle : deploymentHandles) {
			final CompletableFuture<Void> slotAssigned = deploymentHandle.getSlotExecutionVertexAssignment().getLogicalSlotFuture()

				/*************************************************
				 *
				 *  注释： 调用 assignResourceOrHandleError 来获取 申请到的 slot， 有可能获取不到
				 */
				.handle(assignResourceOrHandleError(deploymentHandle));
			slotAssignedFutures.add(slotAssigned);
		}
		return FutureUtils.waitForAll(slotAssignedFutures);
	}

	/*************************************************
	 *
	 *  注释： 部署
	 */
	private BiFunction<Void, Throwable, Void> deployAll(final List<DeploymentHandle> deploymentHandles) {
		return (ignored, throwable) -> {
			propagateIfNonNull(throwable);

			/*************************************************
			 *
			 *  注释： 遍历的部署每一个 Task
			 */
			for(final DeploymentHandle deploymentHandle : deploymentHandles) {

				// TODO_MA 注释： 获取 slot 申请消息
				final SlotExecutionVertexAssignment slotExecutionVertexAssignment = deploymentHandle.getSlotExecutionVertexAssignment();
				final CompletableFuture<LogicalSlot> slotAssigned = slotExecutionVertexAssignment.getLogicalSlotFuture();
				checkState(slotAssigned.isDone());

				/*************************************************
				 *
				 *  注释： 通过 deployOrHandleError 来进行部署
				 *  当然，部署 Task 的时候，也有可能会报错！
				 *  1、slotAssigned
				 *  2、deploymentHandle
				 *  3、slotExecutionVertexAssignment
				 */
				FutureUtils.assertNoException(slotAssigned.handle(deployOrHandleError(deploymentHandle)));
			}
			return null;
		};
	}

	private static void propagateIfNonNull(final Throwable throwable) {
		if(throwable != null) {
			throw new CompletionException(throwable);
		}
	}

	private BiFunction<LogicalSlot, Throwable, Void> assignResourceOrHandleError(final DeploymentHandle deploymentHandle) {
		final ExecutionVertexVersion requiredVertexVersion = deploymentHandle.getRequiredVertexVersion();
		final ExecutionVertexID executionVertexId = deploymentHandle.getExecutionVertexId();

		return (logicalSlot, throwable) -> {
			if(executionVertexVersioner.isModified(requiredVertexVersion)) {
				log.debug("Refusing to assign slot to execution vertex {} because this deployment was " + "superseded by another deployment",
					executionVertexId);
				releaseSlotIfPresent(logicalSlot);
				return null;
			}

			if(throwable == null) {
				final ExecutionVertex executionVertex = getExecutionVertex(executionVertexId);
				final boolean sendScheduleOrUpdateConsumerMessage = deploymentHandle.getDeploymentOption().sendScheduleOrUpdateConsumerMessage();

				/*************************************************
				 *
				 *  注释： 注册输入分区
				 */
				executionVertex.getCurrentExecutionAttempt()
					.registerProducedPartitions(logicalSlot.getTaskManagerLocation(), sendScheduleOrUpdateConsumerMessage);

				/*************************************************
				 *
				 *  注释： 分配 slot 资源
				 */
				executionVertex.tryAssignResource(logicalSlot);
			} else {

				/*************************************************
				 *
				 *  注释： 报错处理
				 */
				handleTaskDeploymentFailure(executionVertexId, maybeWrapWithNoResourceAvailableException(throwable));
			}
			return null;
		};
	}

	private void releaseSlotIfPresent(@Nullable final LogicalSlot logicalSlot) {
		if(logicalSlot != null) {
			logicalSlot.releaseSlot(null);
		}
	}

	private void handleTaskDeploymentFailure(final ExecutionVertexID executionVertexId, final Throwable error) {
		executionVertexOperations.markFailed(getExecutionVertex(executionVertexId), error);
	}

	private static Throwable maybeWrapWithNoResourceAvailableException(final Throwable failure) {
		final Throwable strippedThrowable = ExceptionUtils.stripCompletionException(failure);
		if(strippedThrowable instanceof TimeoutException) {
			return new NoResourceAvailableException(
				"Could not allocate the required slot within slot request timeout. " + "Please make sure that the cluster has enough resources.",
				failure);
		} else {
			return failure;
		}
	}

	private BiFunction<Object, Throwable, Void> deployOrHandleError(final DeploymentHandle deploymentHandle) {
		final ExecutionVertexVersion requiredVertexVersion = deploymentHandle.getRequiredVertexVersion();

		// TODO_MA 注释： 获取 ExecutionVertexID
		// TODO_MA 注释： 在 ExecutionGraph 中，一个 ExecutionVertex 对应到要启动一个 Task
		final ExecutionVertexID executionVertexId = requiredVertexVersion.getExecutionVertexId();

		return (ignored, throwable) -> {
			if(executionVertexVersioner.isModified(requiredVertexVersion)) {
				log.debug("Refusing to deploy execution vertex {} because this deployment was " + "superseded by another deployment",
					executionVertexId);
				return null;
			}

			if(throwable == null) {

				/*************************************************
				 *
				 *  注释： 部署 Task（到时候根据 ExecutionVertexID 来确定 Task）
				 */
				deployTaskSafe(executionVertexId);
			} else {

				/*************************************************
				 *
				 *  注释： 部署 Task 报错处理
				 */
				handleTaskDeploymentFailure(executionVertexId, throwable);
			}
			return null;
		};
	}

	/*************************************************
	 *
	 *  注释： 当初在提交一个 job 之后，会先启动 JobMaster，在初始化 JobMaster
	 *  同时创建一个 Shceduler = DefaultScheduler
	 *  在被创建的时候： 同时会把 JobGraph 变成 ExecutionGraph
	 */
	private void deployTaskSafe(final ExecutionVertexID executionVertexId) {
		try {

			/*************************************************
			 *
			 *  注释： 根据 ExecutionVertexId 获取 ExecutionVertex
			 */
			final ExecutionVertex executionVertex = getExecutionVertex(executionVertexId);

			/*************************************************
			 *
			 *  注释： 一个 Task 执行一个 ExecutionVertex
			 *  executionVertexOperations = DefaultExecutionVertexOperations
			 */
			executionVertexOperations.deploy(executionVertex);

		} catch(Throwable e) {
			handleTaskDeploymentFailure(executionVertexId, e);
		}
	}

	private void notifyCoordinatorOfCancellation(ExecutionVertex vertex) {
		// this method makes a best effort to filter out duplicate notifications, meaning cases where
		// the coordinator was already notified for that specific task
		// we don't notify if the task is already FAILED, CANCELLING, or CANCELED

		final ExecutionState currentState = vertex.getExecutionState();
		if(currentState == ExecutionState.FAILED || currentState == ExecutionState.CANCELING || currentState == ExecutionState.CANCELED) {
			return;
		}

		for(OperatorCoordinator coordinator : vertex.getJobVertex().getOperatorCoordinators()) {
			coordinator.subtaskFailed(vertex.getParallelSubtaskIndex(), null);
		}
	}
}
