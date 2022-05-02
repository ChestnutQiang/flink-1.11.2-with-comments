/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.WebOptions;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.blob.BlobWriter;
import org.apache.flink.runtime.checkpoint.CheckpointIDCounter;
import org.apache.flink.runtime.checkpoint.CheckpointRecoveryFactory;
import org.apache.flink.runtime.checkpoint.CheckpointStatsTracker;
import org.apache.flink.runtime.checkpoint.CompletedCheckpointStore;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.runtime.checkpoint.hooks.MasterHooks;
import org.apache.flink.runtime.client.JobExecutionException;
import org.apache.flink.runtime.client.JobSubmissionException;
import org.apache.flink.runtime.executiongraph.failover.FailoverStrategy;
import org.apache.flink.runtime.executiongraph.failover.FailoverStrategyLoader;
import org.apache.flink.runtime.executiongraph.failover.flip1.partitionrelease.PartitionReleaseStrategy;
import org.apache.flink.runtime.executiongraph.failover.flip1.partitionrelease.PartitionReleaseStrategyFactoryLoader;
import org.apache.flink.runtime.executiongraph.metrics.DownTimeGauge;
import org.apache.flink.runtime.executiongraph.metrics.RestartTimeGauge;
import org.apache.flink.runtime.executiongraph.metrics.UpTimeGauge;
import org.apache.flink.runtime.executiongraph.restart.RestartStrategy;
import org.apache.flink.runtime.io.network.partition.JobMasterPartitionTracker;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.jsonplan.JsonPlanGenerator;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointingSettings;
import org.apache.flink.runtime.jobmaster.slotpool.SlotProvider;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.StateBackendLoader;
import org.apache.flink.util.DynamicCodeLoadingException;
import org.apache.flink.util.SerializedValue;

import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Utility class to encapsulate the logic of building an {@link ExecutionGraph} from a {@link JobGraph}.
 */
public class ExecutionGraphBuilder {

	/**
	 * Builds the ExecutionGraph from the JobGraph.
	 * If a prior execution graph exists, the JobGraph will be attached. If no prior execution
	 * graph exists, then the JobGraph will become attach to a new empty execution graph.
	 */
	public static ExecutionGraph buildGraph(@Nullable ExecutionGraph prior, JobGraph jobGraph, Configuration jobManagerConfig,
		ScheduledExecutorService futureExecutor, Executor ioExecutor, SlotProvider slotProvider, ClassLoader classLoader,
		CheckpointRecoveryFactory recoveryFactory, Time rpcTimeout, RestartStrategy restartStrategy, MetricGroup metrics, BlobWriter blobWriter,
		Time allocationTimeout, Logger log, ShuffleMaster<?> shuffleMaster,
		JobMasterPartitionTracker partitionTracker) throws JobExecutionException, JobException {

		final FailoverStrategy.Factory failoverStrategy = FailoverStrategyLoader.loadFailoverStrategy(jobManagerConfig, log);

		return buildGraph(prior, jobGraph, jobManagerConfig, futureExecutor, ioExecutor, slotProvider, classLoader, recoveryFactory, rpcTimeout,
			restartStrategy, metrics, blobWriter, allocationTimeout, log, shuffleMaster, partitionTracker, failoverStrategy);
	}

	/*************************************************
	 *
	 *  注释： 构建 ExectionGraph
	 */
	public static ExecutionGraph buildGraph(@Nullable ExecutionGraph prior, JobGraph jobGraph, Configuration jobManagerConfig,
		ScheduledExecutorService futureExecutor, Executor ioExecutor, SlotProvider slotProvider, ClassLoader classLoader,
		CheckpointRecoveryFactory recoveryFactory, Time rpcTimeout, RestartStrategy restartStrategy, MetricGroup metrics, BlobWriter blobWriter,
		Time allocationTimeout, Logger log, ShuffleMaster<?> shuffleMaster, JobMasterPartitionTracker partitionTracker,
		FailoverStrategy.Factory failoverStrategyFactory) throws JobExecutionException, JobException {

		checkNotNull(jobGraph, "job graph cannot be null");

		/*************************************************
		 *
		 *  注释： 从 JobGraph 中获取 JobName 和 JobID
		 */
		final String jobName = jobGraph.getName();
		final JobID jobId = jobGraph.getJobID();

		// TODO_MA 注释： 构建包含 job 信息的 JobInformation 对象
		final JobInformation jobInformation = new JobInformation(jobId, jobName, jobGraph.getSerializedExecutionConfig(),
			jobGraph.getJobConfiguration(), jobGraph.getUserJarBlobKeys(), jobGraph.getClasspaths());

		final int maxPriorAttemptsHistoryLength = jobManagerConfig.getInteger(JobManagerOptions.MAX_ATTEMPTS_HISTORY_SIZE);

		// TODO_MA 注释： 释放 IntermediateResultPartition 的策略: RegionPartitionReleaseStrategy
		final PartitionReleaseStrategy.Factory partitionReleaseStrategyFactory = PartitionReleaseStrategyFactoryLoader
			.loadPartitionReleaseStrategyFactory(jobManagerConfig);

		// create a new execution graph, if none exists so far
		final ExecutionGraph executionGraph;
		try {
			/*************************************************
			 *
			 *  注释： 获取 ExecutionGraph， 只是创建了一个 ExecutionGraph 对象而已
			 */
			executionGraph = (prior != null) ? prior : new ExecutionGraph(jobInformation, futureExecutor, ioExecutor, rpcTimeout,
				restartStrategy, maxPriorAttemptsHistoryLength, failoverStrategyFactory, slotProvider, classLoader, blobWriter,
				allocationTimeout, partitionReleaseStrategyFactory, shuffleMaster, partitionTracker, jobGraph.getScheduleMode());
		} catch(IOException e) {
			throw new JobException("Could not create the ExecutionGraph.", e);
		}

		// set the basic properties

		/*************************************************
		 *
		 *  注释： 设置 ExecutionGraph 的一些基本属性
		 *  1、JsonPlanGenerator.generatePlan(jobGraph) 根据 JobGraph 生成一个 JsonPlan
		 *  2、executionGraph.setJsonPlan(JsonPlan) 把 JsonPlan 设置到 ExecutionGraph
		 */
		try {
			executionGraph.setJsonPlan(JsonPlanGenerator.generatePlan(jobGraph));
		} catch(Throwable t) {
			log.warn("Cannot create JSON plan for job", t);
			// give the graph an empty plan
			executionGraph.setJsonPlan("{}");
		}

		// initialize the vertices that have a master initialization hook
		// file output formats create directories here, input formats create splits

		final long initMasterStart = System.nanoTime();
		log.info("Running initialization on master for job {} ({}).", jobName, jobId);

		/*************************************************
		 *
		 *  注释： 遍历每个 JobVertex 执行初始化
		 */
		for(JobVertex vertex : jobGraph.getVertices()) {

			// TODO_MA 注释： 获取 JobVertex 的 invokableClassName
			String executableClass = vertex.getInvokableClassName();
			if(executableClass == null || executableClass.isEmpty()) {
				throw new JobSubmissionException(jobId, "The vertex " + vertex.getID() + " (" + vertex.getName() + ") has no invokable class.");
			}

			/*************************************************
			 *
			 *  注释： 如果是 InputFormatVertex 和 OutputFormatVertex， 则可以进行一些初始化
			 *  1、File output format 在这一步准备好输出目录
			 *  2、Input splits 在这一步创建对应的 splits
			 */
			try {
				vertex.initializeOnMaster(classLoader);
			} catch(Throwable t) {
				throw new JobExecutionException(jobId, "Cannot initialize task '" + vertex.getName() + "': " + t.getMessage(), t);
			}
		}

		log.info("Successfully ran initialization on master in {} ms.", (System.nanoTime() - initMasterStart) / 1_000_000);

		// topologically sort the job vertices and attach the graph to the existing one
		List<JobVertex> sortedTopology = jobGraph.getVerticesSortedTopologicallyFromSources();
		if(log.isDebugEnabled()) {
			log.debug("Adding {} vertices from job graph {} ({}).", sortedTopology.size(), jobName, jobId);
		}

		/*************************************************
		 *
		 *  注释： 核心
		 *  ExecutionGraph 事实上只是改动了 JobGraph 的每个节点，而没有对整个拓扑结构进行变动，
		 *  所以代码里只是挨个遍历 jobVertex 并进行处理
		 */
		executionGraph.attachJobGraph(sortedTopology);

		if(log.isDebugEnabled()) {
			log.debug("Successfully created execution graph from job graph {} ({}).", jobName, jobId);
		}

		/*************************************************
		 *
		 *  注释： 配置 state 的 checkpoint
		 */
		// configure the state checkpointing
		JobCheckpointingSettings snapshotSettings = jobGraph.getCheckpointingSettings();
		if(snapshotSettings != null) {

			// TODO_MA 注释： 只有 source 顶点会成为 triggerVertices.
			// TODO_MA 注释： 需要 “触发 checkpoint” 的顶点，后续 CheckpointCoordinator 发起 checkpoint 时，
			// TODO_MA 注释： 只有这些点会收到 trigger checkpoint 消息。
			List<ExecutionJobVertex> triggerVertices = idToVertex(snapshotSettings.getVerticesToTrigger(), executionGraph);

			// TODO_MA 注释： 所有顶点都是 ackVertices
			// TODO_MA 注释： 需要在 snapshot 完成后，向 CheckpointCoordinator 发送 ack 消息的顶点。
			List<ExecutionJobVertex> ackVertices = idToVertex(snapshotSettings.getVerticesToAcknowledge(), executionGraph);

			// TODO_MA 注释： 所有顶点都是 commitVertices
			// TODO_MA 注释： 需要在 checkpoint 完成后，收到 CheckpointCoordinator “notifyCheckpointComplete” 消息的顶点。
			List<ExecutionJobVertex> confirmVertices = idToVertex(snapshotSettings.getVerticesToConfirm(), executionGraph);

			CompletedCheckpointStore completedCheckpoints;
			CheckpointIDCounter checkpointIdCounter;
			try {
				int maxNumberOfCheckpointsToRetain = jobManagerConfig.getInteger(CheckpointingOptions.MAX_RETAINED_CHECKPOINTS);

				if(maxNumberOfCheckpointsToRetain <= 0) {
					// warning and use 1 as the default value if the setting in
					// state.checkpoints.max-retained-checkpoints is not greater than 0.
					log.warn("The setting for '{} : {}' is invalid. Using default value of {}",
						CheckpointingOptions.MAX_RETAINED_CHECKPOINTS.key(), maxNumberOfCheckpointsToRetain,
						CheckpointingOptions.MAX_RETAINED_CHECKPOINTS.defaultValue());

					maxNumberOfCheckpointsToRetain = CheckpointingOptions.MAX_RETAINED_CHECKPOINTS.defaultValue();
				}

				// TODO_MA 注释： 创建 CheckpointStore
				completedCheckpoints = recoveryFactory.createCheckpointStore(jobId, maxNumberOfCheckpointsToRetain, classLoader);
				checkpointIdCounter = recoveryFactory.createCheckpointIDCounter(jobId);
			} catch(Exception e) {
				throw new JobExecutionException(jobId, "Failed to initialize high-availability checkpoint handler", e);
			}

			// Maximum number of remembered checkpoints
			int historySize = jobManagerConfig.getInteger(WebOptions.CHECKPOINTS_HISTORY_SIZE);

			/*************************************************
			 *
			 *  注释： 状态追踪器初始化
			 */
			CheckpointStatsTracker checkpointStatsTracker = new CheckpointStatsTracker(historySize, ackVertices,
				snapshotSettings.getCheckpointCoordinatorConfiguration(), metrics);

			/*************************************************
			 *
			 *  注释： 状态后端
			 */
			// load the state backend from the application settings
			final StateBackend applicationConfiguredBackend;
			final SerializedValue<StateBackend> serializedAppConfigured = snapshotSettings.getDefaultStateBackend();

			if(serializedAppConfigured == null) {
				applicationConfiguredBackend = null;
			} else {
				try {
					// TODO_MA 注释： 状态后端： StateBackend 初始化
					applicationConfiguredBackend = serializedAppConfigured.deserializeValue(classLoader);
				} catch(IOException | ClassNotFoundException e) {
					throw new JobExecutionException(jobId, "Could not deserialize application-defined state backend.", e);
				}
			}

			/*************************************************
			 *
			 *  注释： 创建 StateBackend
			 */
			final StateBackend rootBackend;
			try {
				rootBackend = StateBackendLoader
					.fromApplicationOrConfigOrDefault(applicationConfiguredBackend, jobManagerConfig, classLoader, log);
			} catch(IllegalConfigurationException | IOException | DynamicCodeLoadingException e) {
				throw new JobExecutionException(jobId, "Could not instantiate configured state backend", e);
			}

			// instantiate the user-defined checkpoint hooks

			final SerializedValue<MasterTriggerRestoreHook.Factory[]> serializedHooks = snapshotSettings.getMasterHooks();
			final List<MasterTriggerRestoreHook<?>> hooks;

			if(serializedHooks == null) {
				hooks = Collections.emptyList();
			} else {
				final MasterTriggerRestoreHook.Factory[] hookFactories;
				try {
					hookFactories = serializedHooks.deserializeValue(classLoader);
				} catch(IOException | ClassNotFoundException e) {
					throw new JobExecutionException(jobId, "Could not instantiate user-defined checkpoint hooks", e);
				}

				final Thread thread = Thread.currentThread();
				final ClassLoader originalClassLoader = thread.getContextClassLoader();
				thread.setContextClassLoader(classLoader);

				try {
					hooks = new ArrayList<>(hookFactories.length);
					for(MasterTriggerRestoreHook.Factory factory : hookFactories) {
						hooks.add(MasterHooks.wrapHook(factory.create(), classLoader));
					}
				} finally {
					thread.setContextClassLoader(originalClassLoader);
				}
			}

			/*************************************************
			 *
			 *  注释： 获取 CheckpointCoordinatorConfiguration
			 */
			final CheckpointCoordinatorConfiguration chkConfig = snapshotSettings.getCheckpointCoordinatorConfiguration();

			/*************************************************
			 *
			 *  注释： 设置 executionGraph 的 checkpoint 相关配置
			 */
			executionGraph.enableCheckpointing(chkConfig, triggerVertices, ackVertices, confirmVertices,
				hooks, checkpointIdCounter, completedCheckpoints, rootBackend, checkpointStatsTracker);
		}

		// create all the metrics for the Execution Graph

		metrics.gauge(RestartTimeGauge.METRIC_NAME, new RestartTimeGauge(executionGraph));
		metrics.gauge(DownTimeGauge.METRIC_NAME, new DownTimeGauge(executionGraph));
		metrics.gauge(UpTimeGauge.METRIC_NAME, new UpTimeGauge(executionGraph));

		executionGraph.getFailoverStrategy().registerMetrics(metrics);

		/*************************************************
		 *
		 *  注释： 返回 ExecutioinGraph
		 */
		return executionGraph;
	}

	private static List<ExecutionJobVertex> idToVertex(List<JobVertexID> jobVertices,
		ExecutionGraph executionGraph) throws IllegalArgumentException {

		List<ExecutionJobVertex> result = new ArrayList<>(jobVertices.size());

		for(JobVertexID id : jobVertices) {
			ExecutionJobVertex vertex = executionGraph.getJobVertex(id);
			if(vertex != null) {
				result.add(vertex);
			} else {
				throw new IllegalArgumentException("The snapshot checkpointing settings refer to non-existent vertex " + id);
			}
		}

		return result;
	}

	// ------------------------------------------------------------------------

	/**
	 * This class is not supposed to be instantiated.
	 */
	private ExecutionGraphBuilder() {
	}
}
