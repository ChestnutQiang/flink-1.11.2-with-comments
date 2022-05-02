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

package org.apache.flink.runtime.resourcemanager.slotmanager;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.metrics.groups.SlotManagerMetricGroup;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.WorkerResourceSpec;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.exceptions.UnfulfillableSlotRequestException;
import org.apache.flink.runtime.resourcemanager.registration.TaskExecutorConnection;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.exceptions.SlotAllocationException;
import org.apache.flink.runtime.taskexecutor.exceptions.SlotOccupiedException;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.MathUtils;
import org.apache.flink.util.OptionalConsumer;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implementation of {@link SlotManager}.
 */
public class SlotManagerImpl implements SlotManager {
	private static final Logger LOG = LoggerFactory.getLogger(SlotManagerImpl.class);

	/**
	 * Scheduled executor for timeouts.
	 */
	private final ScheduledExecutor scheduledExecutor;

	/**
	 * Timeout for slot requests to the task manager.
	 */
	private final Time taskManagerRequestTimeout;

	/**
	 * Timeout after which an allocation is discarded.
	 */
	private final Time slotRequestTimeout;

	/**
	 * Timeout after which an unused TaskManager is released.
	 */
	private final Time taskManagerTimeout;

	/**
	 * Map for all registered slots.
	 * // TODO_MA 注释： 只要是 TaskExecutor 注册上线了，必然会进行 slot汇报，
	 * 汇报的时候，所有的　slot 都执行注册，其实就是被管理在这个 slots 集合中
	 */
	private final HashMap<SlotID, TaskManagerSlot> slots;

	/**
	 * Index of all currently free slots.
	 * // TODO_MA 注释： 空闲， 在执行完 Task 之后，被释放的 slot，放入 freeSlots 中
	 */
	private final LinkedHashMap<SlotID, TaskManagerSlot> freeSlots;

	/**
	 * All currently registered task managers.
	 */
	private final HashMap<InstanceID, TaskManagerRegistration> taskManagerRegistrations;

	/**
	 * Map of fulfilled and active allocations for request deduplication purposes.
	 */
	private final HashMap<AllocationID, SlotID> fulfilledSlotRequests;

	/**
	 * Map of pending/unfulfilled slot allocation requests.
	 */
	private final HashMap<AllocationID, PendingSlotRequest> pendingSlotRequests;

	/*************************************************
	 *
	 *  注释： 资源不足的时候会通过 ResourceActions#allocateResource(ResourceProfile)
	 *  申请新的资源（可能启动新的 TaskManager，也可能什么也不做）， 这些新申请的资源会
	 *  被封装为 PendingTaskManagerSlot
	 */
	private final HashMap<TaskManagerSlotId, PendingTaskManagerSlot> pendingSlots;

	private final SlotMatchingStrategy slotMatchingStrategy;

	/**
	 * ResourceManager's id.
	 */
	private ResourceManagerId resourceManagerId;

	/**
	 * Executor for future callbacks which have to be "synchronized".
	 */
	private Executor mainThreadExecutor;

	/**
	 * Callbacks for resource (de-)allocations.
	 */
	private ResourceActions resourceActions;

	private ScheduledFuture<?> taskManagerTimeoutCheck;

	private ScheduledFuture<?> slotRequestTimeoutCheck;

	/**
	 * True iff the component has been started.
	 */
	private boolean started;

	/**
	 * // TODO_MA 注释： 仅当每个产生的结果分区被消耗或失败时才释放任务执行器。
	 * Release task executor only when each produced result partition is either consumed or failed.
	 */
	private final boolean waitResultConsumedBeforeRelease;

	/**
	 * Defines the max limitation of the total number of slots.
	 */
	private final int maxSlotNum;

	/**
	 * If true, fail unfulfillable slot requests immediately. Otherwise, allow unfulfillable request to pend.
	 * A slot request is considered unfulfillable if it cannot be fulfilled by neither a slot that is already registered
	 * (including allocated ones) nor a pending slot that the {@link ResourceActions} can allocate.
	 */
	private boolean failUnfulfillableRequest = true;

	/**
	 * The default resource spec of workers to request.
	 */
	private final WorkerResourceSpec defaultWorkerResourceSpec;

	private final int numSlotsPerWorker;

	private final ResourceProfile defaultSlotResourceProfile;

	private final SlotManagerMetricGroup slotManagerMetricGroup;

	public SlotManagerImpl(ScheduledExecutor scheduledExecutor, SlotManagerConfiguration slotManagerConfiguration,
		SlotManagerMetricGroup slotManagerMetricGroup) {

		this.scheduledExecutor = Preconditions.checkNotNull(scheduledExecutor);

		Preconditions.checkNotNull(slotManagerConfiguration);
		this.slotMatchingStrategy = slotManagerConfiguration.getSlotMatchingStrategy();
		this.taskManagerRequestTimeout = slotManagerConfiguration.getTaskManagerRequestTimeout();
		this.slotRequestTimeout = slotManagerConfiguration.getSlotRequestTimeout();
		this.taskManagerTimeout = slotManagerConfiguration.getTaskManagerTimeout();
		this.waitResultConsumedBeforeRelease = slotManagerConfiguration.isWaitResultConsumedBeforeRelease();
		this.defaultWorkerResourceSpec = slotManagerConfiguration.getDefaultWorkerResourceSpec();
		this.numSlotsPerWorker = slotManagerConfiguration.getNumSlotsPerWorker();

		/*************************************************
		 *
		 *  注释： 生成资源配置
		 */
		this.defaultSlotResourceProfile = generateDefaultSlotResourceProfile(defaultWorkerResourceSpec, numSlotsPerWorker);

		this.slotManagerMetricGroup = Preconditions.checkNotNull(slotManagerMetricGroup);
		this.maxSlotNum = slotManagerConfiguration.getMaxSlotNum();

		slots = new HashMap<>(16);
		freeSlots = new LinkedHashMap<>(16);
		taskManagerRegistrations = new HashMap<>(4);
		fulfilledSlotRequests = new HashMap<>(16);
		pendingSlotRequests = new HashMap<>(16);
		pendingSlots = new HashMap<>(16);

		resourceManagerId = null;
		resourceActions = null;
		mainThreadExecutor = null;
		taskManagerTimeoutCheck = null;
		slotRequestTimeoutCheck = null;

		started = false;
	}

	@Override
	public int getNumberRegisteredSlots() {
		return slots.size();
	}

	@Override
	public int getNumberRegisteredSlotsOf(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if(taskManagerRegistration != null) {
			return taskManagerRegistration.getNumberRegisteredSlots();
		} else {
			return 0;
		}
	}

	@Override
	public int getNumberFreeSlots() {
		return freeSlots.size();
	}

	@Override
	public int getNumberFreeSlotsOf(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if(taskManagerRegistration != null) {
			return taskManagerRegistration.getNumberFreeSlots();
		} else {
			return 0;
		}
	}

	@Override
	public Map<WorkerResourceSpec, Integer> getRequiredResources() {
		final int pendingWorkerNum = MathUtils.divideRoundUp(pendingSlots.size(), numSlotsPerWorker);
		return pendingWorkerNum > 0 ? Collections.singletonMap(defaultWorkerResourceSpec, pendingWorkerNum) : Collections.emptyMap();
	}

	@Override
	public ResourceProfile getRegisteredResource() {
		return getResourceFromNumSlots(getNumberRegisteredSlots());
	}

	@Override
	public ResourceProfile getRegisteredResourceOf(InstanceID instanceID) {
		return getResourceFromNumSlots(getNumberRegisteredSlotsOf(instanceID));
	}

	@Override
	public ResourceProfile getFreeResource() {
		return getResourceFromNumSlots(getNumberFreeSlots());
	}

	@Override
	public ResourceProfile getFreeResourceOf(InstanceID instanceID) {
		return getResourceFromNumSlots(getNumberFreeSlotsOf(instanceID));
	}

	private ResourceProfile getResourceFromNumSlots(int numSlots) {
		if(numSlots < 0 || defaultSlotResourceProfile == null) {
			return ResourceProfile.UNKNOWN;
		} else {
			return defaultSlotResourceProfile.multiply(numSlots);
		}
	}

	@VisibleForTesting
	public int getNumberPendingTaskManagerSlots() {
		return pendingSlots.size();
	}

	@Override
	public int getNumberPendingSlotRequests() {
		return pendingSlotRequests.size();
	}

	@VisibleForTesting
	public int getNumberAssignedPendingTaskManagerSlots() {
		return (int) pendingSlots.values().stream().filter(slot -> slot.getAssignedPendingSlotRequest() != null).count();
	}

	// ---------------------------------------------------------------------------------------------
	// Component lifecycle methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts the slot manager with the given leader id and resource manager actions.
	 *
	 * @param newResourceManagerId  to use for communication with the task managers
	 * @param newMainThreadExecutor to use to run code in the ResourceManager's main thread
	 * @param newResourceActions    to use for resource (de-)allocations
	 */
	@Override
	public void start(ResourceManagerId newResourceManagerId, Executor newMainThreadExecutor, ResourceActions newResourceActions) {
		LOG.info("Starting the SlotManager.");

		this.resourceManagerId = Preconditions.checkNotNull(newResourceManagerId);
		mainThreadExecutor = Preconditions.checkNotNull(newMainThreadExecutor);
		resourceActions = Preconditions.checkNotNull(newResourceActions);

		started = true;

		/*************************************************
		 *
		 *  注释： 开启第一个定时任务： checkTaskManagerTimeouts， 检查 TaskManager 的心跳
		 *  taskManagerTimeout = resourcemanager.taskmanager-timeout = 30000
		 */
		taskManagerTimeoutCheck = scheduledExecutor
			.scheduleWithFixedDelay(() -> mainThreadExecutor.execute(() -> checkTaskManagerTimeouts()), 0L, taskManagerTimeout.toMilliseconds(),
				TimeUnit.MILLISECONDS);

		/*************************************************
		 *
		 *  注释： 开启第二个定时任务： checkSlotRequestTimeouts， 检查 SplotRequest 超时处理
		 *  slotRequestTimeout = slot.request.timeout = 5L * 60L * 1000L
		 */
		slotRequestTimeoutCheck = scheduledExecutor
			.scheduleWithFixedDelay(() -> mainThreadExecutor.execute(() -> checkSlotRequestTimeouts()), 0L, slotRequestTimeout.toMilliseconds(),
				TimeUnit.MILLISECONDS);

		registerSlotManagerMetrics();
	}

	private void registerSlotManagerMetrics() {
		slotManagerMetricGroup.gauge(MetricNames.TASK_SLOTS_AVAILABLE, () -> (long) getNumberFreeSlots());
		slotManagerMetricGroup.gauge(MetricNames.TASK_SLOTS_TOTAL, () -> (long) getNumberRegisteredSlots());
	}

	/**
	 * Suspends the component. This clears the internal state of the slot manager.
	 */
	@Override
	public void suspend() {
		LOG.info("Suspending the SlotManager.");

		// stop the timeout checks for the TaskManagers and the SlotRequests
		if(taskManagerTimeoutCheck != null) {
			taskManagerTimeoutCheck.cancel(false);
			taskManagerTimeoutCheck = null;
		}

		if(slotRequestTimeoutCheck != null) {
			slotRequestTimeoutCheck.cancel(false);
			slotRequestTimeoutCheck = null;
		}

		for(PendingSlotRequest pendingSlotRequest : pendingSlotRequests.values()) {
			cancelPendingSlotRequest(pendingSlotRequest);
		}

		pendingSlotRequests.clear();

		ArrayList<InstanceID> registeredTaskManagers = new ArrayList<>(taskManagerRegistrations.keySet());

		for(InstanceID registeredTaskManager : registeredTaskManagers) {
			unregisterTaskManager(registeredTaskManager, new SlotManagerException("The slot manager is being suspended."));
		}

		resourceManagerId = null;
		resourceActions = null;
		started = false;
	}

	/**
	 * Closes the slot manager.
	 *
	 * @throws Exception if the close operation fails
	 */
	@Override
	public void close() throws Exception {
		LOG.info("Closing the SlotManager.");

		suspend();
		slotManagerMetricGroup.close();
	}

	// ---------------------------------------------------------------------------------------------
	// Public API
	// ---------------------------------------------------------------------------------------------

	/**
	 * Requests a slot with the respective resource profile.
	 *
	 * @param slotRequest specifying the requested slot specs
	 * @return true if the slot request was registered; false if the request is a duplicate
	 * @throws ResourceManagerException if the slot request failed (e.g. not enough resources left)
	 */
	@Override
	public boolean registerSlotRequest(SlotRequest slotRequest) throws ResourceManagerException {

		// TODO_MA 注释： 检测 SlotManagerImpl 是否已经启动
		checkInit();

		// TODO_MA 注释： 检测 slot申请 请求是否重复
		if(checkDuplicateRequest(slotRequest.getAllocationId())) {
			LOG.debug("Ignoring a duplicate slot request with allocation id {}.", slotRequest.getAllocationId());
			return false;
		} else {
			// TODO_MA 注释： 将请求封装为 PendingSlotRequest
			PendingSlotRequest pendingSlotRequest = new PendingSlotRequest(slotRequest);

			// TODO_MA 注释： 请请求管理起来，用来判断请求是否重复
			pendingSlotRequests.put(slotRequest.getAllocationId(), pendingSlotRequest);
			try {

				/*************************************************
				 *
				 *  注释： 调用内部实现
				 */
				internalRequestSlot(pendingSlotRequest);
			} catch(ResourceManagerException e) {
				// requesting the slot failed --> remove pending slot request
				pendingSlotRequests.remove(slotRequest.getAllocationId());
				throw new ResourceManagerException("Could not fulfill slot request " + slotRequest.getAllocationId() + '.', e);
			}
			return true;
		}
	}

	/**
	 * Cancels and removes a pending slot request with the given allocation id. If there is no such
	 * pending request, then nothing is done.
	 *
	 * @param allocationId identifying the pending slot request
	 * @return True if a pending slot request was found; otherwise false
	 */
	@Override
	public boolean unregisterSlotRequest(AllocationID allocationId) {
		checkInit();

		// TODO_MA 注释： 从 pendingSlotRequests 中移除 PendingSlotRequest
		PendingSlotRequest pendingSlotRequest = pendingSlotRequests.remove(allocationId);

		if(null != pendingSlotRequest) {
			LOG.debug("Cancel slot request {}.", allocationId);

			// TODO_MA 注释： 取消请求
			cancelPendingSlotRequest(pendingSlotRequest);

			return true;
		} else {
			LOG.debug("No pending slot request with allocation id {} found. Ignoring unregistration request.", allocationId);

			return false;
		}
	}

	/**
	 * Registers a new task manager at the slot manager. This will make the task managers slots
	 * known and, thus, available for allocation.
	 *
	 * @param taskExecutorConnection for the new task manager
	 * @param initialSlotReport      for the new task manager
	 * @return True if the task manager has not been registered before and is registered successfully; otherwise false
	 */
	@Override
	public boolean registerTaskManager(final TaskExecutorConnection taskExecutorConnection, SlotReport initialSlotReport) {

		// TODO_MA 注释： 确保 SlotManager 是运行状态
		checkInit();
		LOG.debug("Registering TaskManager {} under {} at the SlotManager.", taskExecutorConnection.getResourceID(),
			taskExecutorConnection.getInstanceID());

		// we identify task managers by their instance id
		if(taskManagerRegistrations.containsKey(taskExecutorConnection.getInstanceID())) {

			// TODO_MA 注释： 报告 Slot 的状态
			reportSlotStatus(taskExecutorConnection.getInstanceID(), initialSlotReport);
			return false;
		} else {
			if(isMaxSlotNumExceededAfterRegistration(initialSlotReport)) {
				LOG.info("The total number of slots exceeds the max limitation {}, release the excess resource.", maxSlotNum);
				resourceActions.releaseResource(taskExecutorConnection.getInstanceID(),
					new FlinkException("The total number of slots exceeds the max limitation."));
				return false;
			}

			// first register the TaskManager
			ArrayList<SlotID> reportedSlots = new ArrayList<>();

			for(SlotStatus slotStatus : initialSlotReport) {
				reportedSlots.add(slotStatus.getSlotID());
			}

			/*************************************************
			 *
			 *  注释： 完成 TaskManager 注册
			 */
			TaskManagerRegistration taskManagerRegistration = new TaskManagerRegistration(taskExecutorConnection, reportedSlots);
			taskManagerRegistrations.put(taskExecutorConnection.getInstanceID(), taskManagerRegistration);

			/*************************************************
			 *
			 *  注释： 完成 Slot 注册
			 */
			// next register the new slots
			for(SlotStatus slotStatus : initialSlotReport) {
				registerSlot(slotStatus.getSlotID(), slotStatus.getAllocationID(), slotStatus.getJobID(), slotStatus.getResourceProfile(),
					taskExecutorConnection);
			}
			return true;
		}
	}

	@Override
	public boolean unregisterTaskManager(InstanceID instanceId, Exception cause) {
		checkInit();

		LOG.debug("Unregister TaskManager {} from the SlotManager.", instanceId);

		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.remove(instanceId);

		if(null != taskManagerRegistration) {
			internalUnregisterTaskManager(taskManagerRegistration, cause);

			return true;
		} else {
			LOG.debug("There is no task manager registered with instance ID {}. Ignoring this message.", instanceId);

			return false;
		}
	}

	/**
	 * // TODO_MA 注释： 报告由给定实例ID标识的 task manager 的 current slot allocations
	 * Reports the current slot allocations for a task manager identified by the given instance id.
	 *
	 * @param instanceId identifying the task manager for which to report the slot status
	 * @param slotReport containing the status for all of its slots
	 * @return true if the slot status has been updated successfully, otherwise false
	 */
	@Override
	public boolean reportSlotStatus(InstanceID instanceId, SlotReport slotReport) {
		checkInit();

		// TODO_MA 注释： 获取 InstanceID 对应的 TaskManager 的注册对象
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if(null != taskManagerRegistration) {
			LOG.debug("Received slot report from instance {}: {}.", instanceId, slotReport);

			/*************************************************
			 *
			 *  注释： 每次心跳，汇报过来的某一个 TaskManager 的 Slot 的汇报
			 */
			for(SlotStatus slotStatus : slotReport) {
				updateSlot(slotStatus.getSlotID(), slotStatus.getAllocationID(), slotStatus.getJobID());
			}

			return true;
		} else {
			LOG.debug("Received slot report for unknown task manager with instance id {}. Ignoring this report.", instanceId);

			return false;
		}
	}

	/**
	 * Free the given slot from the given allocation. If the slot is still allocated by the given
	 * allocation id, then the slot will be marked as free and will be subject to new slot requests.
	 *
	 * @param slotId       identifying the slot to free
	 * @param allocationId with which the slot is presumably allocated
	 */
	@Override
	public void freeSlot(SlotID slotId, AllocationID allocationId) {
		checkInit();

		TaskManagerSlot slot = slots.get(slotId);

		if(null != slot) {
			if(slot.getState() == TaskManagerSlot.State.ALLOCATED) {
				if(Objects.equals(allocationId, slot.getAllocationId())) {

					TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(slot.getInstanceId());

					if(taskManagerRegistration == null) {
						throw new IllegalStateException(
							"Trying to free a slot from a TaskManager " + slot.getInstanceId() + " which has not been registered.");
					}

					updateSlotState(slot, taskManagerRegistration, null, null);
				} else {
					LOG.debug(
						"Received request to free slot {} with expected allocation id {}, " + "but actual allocation id {} differs. Ignoring the request.",
						slotId, allocationId, slot.getAllocationId());
				}
			} else {
				LOG.debug("Slot {} has not been allocated.", allocationId);
			}
		} else {
			LOG.debug("Trying to free a slot {} which has not been registered. Ignoring this message.", slotId);
		}
	}

	@Override
	public void setFailUnfulfillableRequest(boolean failUnfulfillableRequest) {
		if(!this.failUnfulfillableRequest && failUnfulfillableRequest) {
			// fail unfulfillable pending requests
			Iterator<Map.Entry<AllocationID, PendingSlotRequest>> slotRequestIterator = pendingSlotRequests.entrySet().iterator();
			while(slotRequestIterator.hasNext()) {
				PendingSlotRequest pendingSlotRequest = slotRequestIterator.next().getValue();
				if(pendingSlotRequest.getAssignedPendingTaskManagerSlot() != null) {
					continue;
				}
				if(!isFulfillableByRegisteredOrPendingSlots(pendingSlotRequest.getResourceProfile())) {
					slotRequestIterator.remove();
					resourceActions.notifyAllocationFailure(pendingSlotRequest.getJobId(), pendingSlotRequest.getAllocationId(),
						new UnfulfillableSlotRequestException(pendingSlotRequest.getAllocationId(), pendingSlotRequest.getResourceProfile()));
				}
			}
		}
		this.failUnfulfillableRequest = failUnfulfillableRequest;
	}

	// ---------------------------------------------------------------------------------------------
	// Behaviour methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Finds a matching slot request for a given resource profile. If there is no such request,
	 * the method returns null.
	 *
	 * <p>Note: If you want to change the behaviour of the slot manager wrt slot allocation and
	 * request fulfillment, then you should override this method.
	 *
	 * @param slotResourceProfile defining the resources of an available slot
	 * @return A matching slot request which can be deployed in a slot with the given resource
	 * profile. Null if there is no such slot request pending.
	 */
	private PendingSlotRequest findMatchingRequest(ResourceProfile slotResourceProfile) {

		for(PendingSlotRequest pendingSlotRequest : pendingSlotRequests.values()) {
			if(!pendingSlotRequest.isAssigned() && slotResourceProfile.isMatching(pendingSlotRequest.getResourceProfile())) {
				return pendingSlotRequest;
			}
		}

		return null;
	}

	/**
	 * Finds a matching slot for a given resource profile. A matching slot has at least as many
	 * resources available as the given resource profile. If there is no such slot available, then
	 * the method returns null.
	 *
	 * <p>Note: If you want to change the behaviour of the slot manager wrt slot allocation and
	 * request fulfillment, then you should override this method.
	 *
	 * @param requestResourceProfile specifying the resource requirements for the a slot request
	 * @return A matching slot which fulfills the given resource profile. {@link Optional#empty()}
	 * if there is no such slot available.
	 */
	private Optional<TaskManagerSlot> findMatchingSlot(ResourceProfile requestResourceProfile) {

		/*************************************************
		 *
		 *  注释： 先从 Free 状态的 Slot 中，寻找合适的 Slot
		 */
		final Optional<TaskManagerSlot> optionalMatchingSlot = slotMatchingStrategy
			.findMatchingSlot(requestResourceProfile, freeSlots.values(), this::getNumberRegisteredSlotsOf);

		optionalMatchingSlot.ifPresent(taskManagerSlot -> {
			// sanity check
			Preconditions.checkState(taskManagerSlot.getState() == TaskManagerSlot.State.FREE, "TaskManagerSlot %s is not in state FREE but %s.",
				taskManagerSlot.getSlotId(), taskManagerSlot.getState());

			/*************************************************
			 *
			 *  注释： 既然申请成功了，则从 freeSlots 集合中删除该 申请到的 Slot
			 */
			freeSlots.remove(taskManagerSlot.getSlotId());
		});

		return optionalMatchingSlot;
	}

	// ---------------------------------------------------------------------------------------------
	// Internal slot operations
	// ---------------------------------------------------------------------------------------------

	/**
	 * Registers a slot for the given task manager at the slot manager. The slot is identified by
	 * the given slot id. The given resource profile defines the available resources for the slot.
	 * The task manager connection can be used to communicate with the task manager.
	 *
	 * @param slotId                identifying the slot on the task manager
	 * @param allocationId          which is currently deployed in the slot
	 * @param resourceProfile       of the slot
	 * @param taskManagerConnection to communicate with the remote task manager
	 */
	private void registerSlot(SlotID slotId, AllocationID allocationId, JobID jobId, ResourceProfile resourceProfile,
		TaskExecutorConnection taskManagerConnection) {

		if(slots.containsKey(slotId)) {
			// remove the old slot first
			removeSlot(slotId, new SlotManagerException(
				String.format("Re-registration of slot %s. This indicates that the TaskExecutor has re-connected.", slotId)));
		}

		// TODO_MA 注释： 生成 TaskManagerSlot 并加入到 slots 集合中
		final TaskManagerSlot slot = createAndRegisterTaskManagerSlot(slotId, resourceProfile, taskManagerConnection);

		final PendingTaskManagerSlot pendingTaskManagerSlot;

		if(allocationId == null) {
			// TODO_MA 注释： 这个 slot 还没有被分配，则找到和当前 slot 的计算资源相匹配的 PendingTaskManagerSlot
			pendingTaskManagerSlot = findExactlyMatchingPendingTaskManagerSlot(resourceProfile);
		} else {
			// TODO_MA 注释： 这个 slot 已经被分配了
			pendingTaskManagerSlot = null;
		}

		if(pendingTaskManagerSlot == null) {

			/*************************************************
			 *
			 *  注释： 更新Slot的状态
			 *  1、slot已经被分配了
			 *  2、没有匹配的 PendingTaskManagerSlot
			 */
			updateSlot(slotId, allocationId, jobId);
		} else {

			// TODO_MA 注释： 新注册的 slot 能够满足 PendingTaskManagerSlot 的要求
			pendingSlots.remove(pendingTaskManagerSlot.getTaskManagerSlotId());
			final PendingSlotRequest assignedPendingSlotRequest = pendingTaskManagerSlot.getAssignedPendingSlotRequest();

			// TODO_MA 注释： PendingTaskManagerSlot 可能有关联的 PedningSlotRequest
			if(assignedPendingSlotRequest == null) {

				// TODO_MA 注释： 没有关联的 PedningSlotRequest，则将 slot 是 Free 状态
				handleFreeSlot(slot);
			} else {

				// TODO_MA 注释： 有关联的 PedningSlotRequest，则这个 request 可以被满足，分配 slot
				assignedPendingSlotRequest.unassignPendingTaskManagerSlot();
				allocateSlot(slot, assignedPendingSlotRequest);
			}
		}
	}

	@Nonnull
	private TaskManagerSlot createAndRegisterTaskManagerSlot(SlotID slotId, ResourceProfile resourceProfile,
		TaskExecutorConnection taskManagerConnection) {

		// TODO_MA 注释： 生成 TaskManagerSlot 对象
		final TaskManagerSlot slot = new TaskManagerSlot(slotId, resourceProfile, taskManagerConnection);
		slots.put(slotId, slot);
		return slot;
	}

	@Nullable
	private PendingTaskManagerSlot findExactlyMatchingPendingTaskManagerSlot(ResourceProfile resourceProfile) {
		for(PendingTaskManagerSlot pendingTaskManagerSlot : pendingSlots.values()) {
			if(isPendingSlotExactlyMatchingResourceProfile(pendingTaskManagerSlot, resourceProfile)) {
				return pendingTaskManagerSlot;
			}
		}

		return null;
	}

	private boolean isPendingSlotExactlyMatchingResourceProfile(PendingTaskManagerSlot pendingTaskManagerSlot, ResourceProfile resourceProfile) {
		return pendingTaskManagerSlot.getResourceProfile().equals(resourceProfile);
	}

	private boolean isMaxSlotNumExceededAfterRegistration(SlotReport initialSlotReport) {
		// check if the total number exceed before matching pending slot.
		if(!isMaxSlotNumExceededAfterAdding(initialSlotReport.getNumSlotStatus())) {
			return false;
		}

		// check if the total number exceed slots after consuming pending slot.
		return isMaxSlotNumExceededAfterAdding(getNumNonPendingReportedNewSlots(initialSlotReport));
	}

	private int getNumNonPendingReportedNewSlots(SlotReport slotReport) {
		final Set<TaskManagerSlotId> matchingPendingSlots = new HashSet<>();

		for(SlotStatus slotStatus : slotReport) {
			for(PendingTaskManagerSlot pendingTaskManagerSlot : pendingSlots.values()) {
				if(!matchingPendingSlots.contains(pendingTaskManagerSlot.getTaskManagerSlotId()) && isPendingSlotExactlyMatchingResourceProfile(
					pendingTaskManagerSlot, slotStatus.getResourceProfile())) {
					matchingPendingSlots.add(pendingTaskManagerSlot.getTaskManagerSlotId());
					break; // pendingTaskManagerSlot loop
				}
			}
		}
		return slotReport.getNumSlotStatus() - matchingPendingSlots.size();
	}

	/**
	 * Updates a slot with the given allocation id.
	 *
	 * @param slotId       to update
	 * @param allocationId specifying the current allocation of the slot
	 * @param jobId        specifying the job to which the slot is allocated
	 * @return True if the slot could be updated; otherwise false
	 */
	private boolean updateSlot(SlotID slotId, AllocationID allocationId, JobID jobId) {
		final TaskManagerSlot slot = slots.get(slotId);

		if(slot != null) {
			final TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(slot.getInstanceId());
			if(taskManagerRegistration != null) {

				/*************************************************
				 *
				 *  注释： 更新 Slot 状态
				 */
				updateSlotState(slot, taskManagerRegistration, allocationId, jobId);
				return true;
			} else {
				throw new IllegalStateException(
					"Trying to update a slot from a TaskManager " + slot.getInstanceId() + " which has not been registered.");
			}
		} else {
			LOG.debug("Trying to update unknown slot with slot id {}.", slotId);

			return false;
		}
	}

	private void updateSlotState(TaskManagerSlot slot, TaskManagerRegistration taskManagerRegistration, @Nullable AllocationID allocationId,
		@Nullable JobID jobId) {
		if(null != allocationId) {
			switch(slot.getState()) {
				case PENDING:
					// we have a pending slot request --> check whether we have to reject it
					PendingSlotRequest pendingSlotRequest = slot.getAssignedSlotRequest();

					if(Objects.equals(pendingSlotRequest.getAllocationId(), allocationId)) {
						// we can cancel the slot request because it has been fulfilled
						cancelPendingSlotRequest(pendingSlotRequest);

						// remove the pending slot request, since it has been completed
						pendingSlotRequests.remove(pendingSlotRequest.getAllocationId());

						// TODO_MA 注释： 更新状态，完成申请
						slot.completeAllocation(allocationId, jobId);
					} else {
						// we first have to free the slot in order to set a new allocationId
						slot.clearPendingSlotRequest();
						// set the allocation id such that the slot won't be considered for the pending slot request
						slot.updateAllocation(allocationId, jobId);

						// remove the pending request if any as it has been assigned
						final PendingSlotRequest actualPendingSlotRequest = pendingSlotRequests.remove(allocationId);

						if(actualPendingSlotRequest != null) {
							cancelPendingSlotRequest(actualPendingSlotRequest);
						}

						// this will try to find a new slot for the request
						rejectPendingSlotRequest(pendingSlotRequest,
							new Exception("Task manager reported slot " + slot.getSlotId() + " being already allocated."));
					}

					taskManagerRegistration.occupySlot();
					break;
				case ALLOCATED:
					if(!Objects.equals(allocationId, slot.getAllocationId())) {
						slot.freeSlot();
						slot.updateAllocation(allocationId, jobId);
					}
					break;
				case FREE:
					// the slot is currently free --> it is stored in freeSlots
					freeSlots.remove(slot.getSlotId());
					slot.updateAllocation(allocationId, jobId);
					taskManagerRegistration.occupySlot();
					break;
			}

			fulfilledSlotRequests.put(allocationId, slot.getSlotId());
		} else {
			// no allocation reported
			switch(slot.getState()) {
				case FREE:
					handleFreeSlot(slot);
					break;
				case PENDING:
					// don't do anything because we still have a pending slot request
					break;
				case ALLOCATED:
					AllocationID oldAllocation = slot.getAllocationId();
					slot.freeSlot();
					fulfilledSlotRequests.remove(oldAllocation);
					taskManagerRegistration.freeSlot();

					handleFreeSlot(slot);
					break;
			}
		}
	}

	/**
	 * // TODO_MA 注释： 尝试为给定的插槽请求分配插槽。
	 * Tries to allocate a slot for the given slot request.
	 *
	 * // TODO_MA 注释： 如果没有可用的插槽，则通知资源管理器分配更多资源，并为请求注册超时
	 * If there is no slot available, the resource manager is informed to allocate more resources and a timeout for the request is
	 * registered.
	 *
	 * // TODO_MA 注释： Flink 的 ResourceManager 向 YARN 的 ResourceManager 申请更多的 Container 来启动 TaskExecutor
	 *
	 * @param pendingSlotRequest to allocate a slot for
	 * @throws ResourceManagerException if the slot request failed or is unfulfillable
	 */
	private void internalRequestSlot(PendingSlotRequest pendingSlotRequest) throws ResourceManagerException {
		final ResourceProfile resourceProfile = pendingSlotRequest.getResourceProfile();

		/*************************************************
		 *
		 *  注释： 首先从 FREE 状态的已注册的 slot 中选择符合要求的 slot
		 *  刚才 ResourceManager 通过调用： SolotManagerImpl的 findMatchingSlot() == 逻辑计算（找出一个 最合适的 free 状态的 slot）
		 *  如果代码走第一个分支，那么方法的返回结果中，必然包含： SlotID 和 TaskExecutorID
		 */
		OptionalConsumer.of(findMatchingSlot(resourceProfile)).ifPresent(

			/*************************************************
			 *
			 *  注释： 找到了符合条件的 slot， 进行分配
			 */
			taskManagerSlot -> allocateSlot(taskManagerSlot, pendingSlotRequest))

			// TODO_MA 注释： 如果代码走这儿，证明集群中，已经没有 free 状态的 slot 了。
			.ifNotPresent(

			/*************************************************
			 *
			 *  注释： 使用待处理的任务管理器插槽满足待处理的插槽请求
			 *  如果为 YARN 模式，并且资源不足的时候，会走这个分支
			 */
			() -> fulfillPendingSlotRequestWithPendingTaskManagerSlot(pendingSlotRequest));
	}

	private void fulfillPendingSlotRequestWithPendingTaskManagerSlot(PendingSlotRequest pendingSlotRequest) throws ResourceManagerException {

		// TODO_MA 注释： 资源配置
		ResourceProfile resourceProfile = pendingSlotRequest.getResourceProfile();

		// TODO_MA 注释： 从 PendingTaskManagerSlot 中选择
		Optional<PendingTaskManagerSlot> pendingTaskManagerSlotOptional = findFreeMatchingPendingTaskManagerSlot(resourceProfile);

		// TODO_MA 注释： 如果连 PendingTaskManagerSlot 中都没有
		if(!pendingTaskManagerSlotOptional.isPresent()) {

			/*************************************************
			 *
			 *  注释： 申请资源
			 *  请求 ResourceManager 分配资源，通过 ResourceActions#allocateResource(ResourceProfile) 回调进行
			 */
			pendingTaskManagerSlotOptional = allocateResource(resourceProfile);
		}

		OptionalConsumer.of(pendingTaskManagerSlotOptional)

			/*************************************************
			 *
			 *  注释： 将 PendingTaskManagerSlot 指派给 PendingSlotRequest
			 */.ifPresent(pendingTaskManagerSlot -> assignPendingTaskManagerSlot(pendingSlotRequest, pendingTaskManagerSlot))
			.ifNotPresent(() -> {
				// request can not be fulfilled by any free slot or pending slot that can be allocated,
				// check whether it can be fulfilled by allocated slots
				if(failUnfulfillableRequest && !isFulfillableByRegisteredOrPendingSlots(pendingSlotRequest.getResourceProfile())) {
					throw new UnfulfillableSlotRequestException(pendingSlotRequest.getAllocationId(), pendingSlotRequest.getResourceProfile());
				}
			});
	}

	private Optional<PendingTaskManagerSlot> findFreeMatchingPendingTaskManagerSlot(ResourceProfile requiredResourceProfile) {
		for(PendingTaskManagerSlot pendingTaskManagerSlot : pendingSlots.values()) {
			if(pendingTaskManagerSlot.getAssignedPendingSlotRequest() == null && pendingTaskManagerSlot.getResourceProfile()
				.isMatching(requiredResourceProfile)) {
				return Optional.of(pendingTaskManagerSlot);
			}
		}

		return Optional.empty();
	}

	private boolean isFulfillableByRegisteredOrPendingSlots(ResourceProfile resourceProfile) {
		for(TaskManagerSlot slot : slots.values()) {
			if(slot.getResourceProfile().isMatching(resourceProfile)) {
				return true;
			}
		}

		for(PendingTaskManagerSlot slot : pendingSlots.values()) {
			if(slot.getResourceProfile().isMatching(resourceProfile)) {
				return true;
			}
		}

		return false;
	}

	private boolean isMaxSlotNumExceededAfterAdding(int numNewSlot) {
		return getNumberRegisteredSlots() + getNumberPendingTaskManagerSlots() + numNewSlot > maxSlotNum;
	}

	private Optional<PendingTaskManagerSlot> allocateResource(ResourceProfile requestedSlotResourceProfile) {
		final int numRegisteredSlots = getNumberRegisteredSlots();
		final int numPendingSlots = getNumberPendingTaskManagerSlots();
		if(isMaxSlotNumExceededAfterAdding(numSlotsPerWorker)) {
			LOG.warn("Could not allocate {} more slots. The number of registered and pending slots is {}, while the maximum is {}.",
				numSlotsPerWorker, numPendingSlots + numRegisteredSlots, maxSlotNum);
			return Optional.empty();
		}

		if(!defaultSlotResourceProfile.isMatching(requestedSlotResourceProfile)) {
			// requested resource profile is unfulfillable
			return Optional.empty();
		}

		/*************************************************
		 *
		 *  注释： 申请资源
		 */
		if(!resourceActions.allocateResource(defaultWorkerResourceSpec)) {
			// resource cannot be allocated
			return Optional.empty();
		}

		PendingTaskManagerSlot pendingTaskManagerSlot = null;
		for(int i = 0; i < numSlotsPerWorker; ++i) {
			pendingTaskManagerSlot = new PendingTaskManagerSlot(defaultSlotResourceProfile);
			pendingSlots.put(pendingTaskManagerSlot.getTaskManagerSlotId(), pendingTaskManagerSlot);
		}

		return Optional.of(Preconditions.checkNotNull(pendingTaskManagerSlot, "At least one pending slot should be created."));
	}

	private void assignPendingTaskManagerSlot(PendingSlotRequest pendingSlotRequest, PendingTaskManagerSlot pendingTaskManagerSlot) {
		pendingTaskManagerSlot.assignPendingSlotRequest(pendingSlotRequest);
		pendingSlotRequest.assignPendingTaskManagerSlot(pendingTaskManagerSlot);
	}

	/**
	 * Allocates the given slot for the given slot request. This entails sending a registration
	 * message to the task manager and treating failures.
	 *
	 * @param taskManagerSlot    to allocate for the given slot request
	 * @param pendingSlotRequest to allocate the given slot for
	 */
	private void allocateSlot(TaskManagerSlot taskManagerSlot, PendingSlotRequest pendingSlotRequest) {

		// TODO_MA 注释： 状态校验，有必要！
		Preconditions.checkState(taskManagerSlot.getState() == TaskManagerSlot.State.FREE);

		/*************************************************
		 *
		 *  注释： 获取和 TaskManager 的链接
		 */
		TaskExecutorConnection taskExecutorConnection = taskManagerSlot.getTaskManagerConnection();
		TaskExecutorGateway gateway = taskExecutorConnection.getTaskExecutorGateway();

		final CompletableFuture<Acknowledge> completableFuture = new CompletableFuture<>();
		final AllocationID allocationId = pendingSlotRequest.getAllocationId();
		final SlotID slotId = taskManagerSlot.getSlotId();
		final InstanceID instanceID = taskManagerSlot.getInstanceId();

		// TODO_MA 注释： TaskManagerSlot 状态变为 PENDING
		taskManagerSlot.assignPendingSlotRequest(pendingSlotRequest);
		pendingSlotRequest.setRequestFuture(completableFuture);

		returnPendingTaskManagerSlotIfAssigned(pendingSlotRequest);

		/*************************************************
		 *
		 *  注释： 获取 TaskManager 的注册信息
		 */
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceID);

		if(taskManagerRegistration == null) {
			throw new IllegalStateException("Could not find a registered task manager for instance id " + instanceID + '.');
		}

		taskManagerRegistration.markUsed();

		/*************************************************
		 *
		 *  注释： 向 TaskExecutor 发起 RPC 请求申请 Slot
		 *  1、gateway： 代表 TaskExecutor
		 *  2、slotId：  代表的是 TaskExecutor 中的某一个 Slot
		 *  3、pendingSlotRequest.getJobId()  对应的 JobMaster
		 */
		// RPC call to the task manager
		CompletableFuture<Acknowledge> requestFuture = gateway
			.requestSlot(slotId, pendingSlotRequest.getJobId(), allocationId, pendingSlotRequest.getResourceProfile(),
				pendingSlotRequest.getTargetAddress(), resourceManagerId, taskManagerRequestTimeout);

		// TODO_MA 注释： 发起向 TaskExecutor 申请 Slot 的 RPC 请求完成
		requestFuture.whenComplete((Acknowledge acknowledge, Throwable throwable) -> {
			if(acknowledge != null) {
				completableFuture.complete(acknowledge);
			} else {
				completableFuture.completeExceptionally(throwable);
			}
		});

		/*************************************************
		 *
		 *  注释：
		 *  PendingSlotRequest 请求完成的回调函数
		 *  PendingSlotRequest 请求完成可能是由于上面 RPC 调用完成，也可能是因为 PendingSlotRequest 被取消
		 */
		completableFuture.whenCompleteAsync((Acknowledge acknowledge, Throwable throwable) -> {
			try {
				// TODO_MA 注释： 申请成功
				if(acknowledge != null) {

					// TODO_MA 注释： 如果请求成功，则取消 pendingSlotRequest，并更新 slot 状态 PENDING -> ALLOCATED
					updateSlot(slotId, allocationId, pendingSlotRequest.getJobId());
				}
				// TODO_MA 注释： 申请失败
				else {
					if(throwable instanceof SlotOccupiedException) {
						SlotOccupiedException exception = (SlotOccupiedException) throwable;

						// TODO_MA 注释： 这个 slot 已经被占用了，更新状态
						updateSlot(slotId, exception.getAllocationId(), exception.getJobId());
					} else {

						// TODO_MA 注释： 请求失败，将 pendingSlotRequest 从 TaskManagerSlot 中移除
						removeSlotRequestFromSlot(slotId, allocationId);
					}

					// TODO_MA 注释： 报错 或者 取消
					if(!(throwable instanceof CancellationException)) {

						// TODO_MA 注释： slot request 请求失败，会进行重试
						handleFailedSlotRequest(slotId, allocationId, throwable);
					} else {

						// TODO_MA 注释： 主动取消
						LOG.debug("Slot allocation request {} has been cancelled.", allocationId, throwable);
					}
				}
			} catch(Exception e) {
				LOG.error("Error while completing the slot allocation.", e);
			}
		}, mainThreadExecutor);
	}

	private void returnPendingTaskManagerSlotIfAssigned(PendingSlotRequest pendingSlotRequest) {
		final PendingTaskManagerSlot pendingTaskManagerSlot = pendingSlotRequest.getAssignedPendingTaskManagerSlot();
		if(pendingTaskManagerSlot != null) {
			pendingTaskManagerSlot.unassignPendingSlotRequest();
			pendingSlotRequest.unassignPendingTaskManagerSlot();
		}
	}

	/**
	 * Handles a free slot. It first tries to find a pending slot request which can be fulfilled.
	 * If there is no such request, then it will add the slot to the set of free slots.
	 *
	 * @param freeSlot to find a new slot request for
	 */
	private void handleFreeSlot(TaskManagerSlot freeSlot) {
		Preconditions.checkState(freeSlot.getState() == TaskManagerSlot.State.FREE);

		PendingSlotRequest pendingSlotRequest = findMatchingRequest(freeSlot.getResourceProfile());

		if(null != pendingSlotRequest) {
			allocateSlot(freeSlot, pendingSlotRequest);
		} else {
			freeSlots.put(freeSlot.getSlotId(), freeSlot);
		}
	}

	/**
	 * Removes the given set of slots from the slot manager.
	 *
	 * @param slotsToRemove identifying the slots to remove from the slot manager
	 * @param cause         for removing the slots
	 */
	private void removeSlots(Iterable<SlotID> slotsToRemove, Exception cause) {
		for(SlotID slotId : slotsToRemove) {
			removeSlot(slotId, cause);
		}
	}

	/**
	 * Removes the given slot from the slot manager.
	 *
	 * @param slotId identifying the slot to remove
	 * @param cause  for removing the slot
	 */
	private void removeSlot(SlotID slotId, Exception cause) {
		TaskManagerSlot slot = slots.remove(slotId);

		if(null != slot) {
			freeSlots.remove(slotId);

			if(slot.getState() == TaskManagerSlot.State.PENDING) {
				// reject the pending slot request --> triggering a new allocation attempt
				rejectPendingSlotRequest(slot.getAssignedSlotRequest(), cause);
			}

			AllocationID oldAllocationId = slot.getAllocationId();

			if(oldAllocationId != null) {
				fulfilledSlotRequests.remove(oldAllocationId);

				resourceActions.notifyAllocationFailure(slot.getJobId(), oldAllocationId, cause);
			}
		} else {
			LOG.debug("There was no slot registered with slot id {}.", slotId);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Internal request handling methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Removes a pending slot request identified by the given allocation id from a slot identified
	 * by the given slot id.
	 *
	 * @param slotId       identifying the slot
	 * @param allocationId identifying the presumable assigned pending slot request
	 */
	private void removeSlotRequestFromSlot(SlotID slotId, AllocationID allocationId) {
		TaskManagerSlot taskManagerSlot = slots.get(slotId);

		if(null != taskManagerSlot) {
			if(taskManagerSlot.getState() == TaskManagerSlot.State.PENDING && Objects
				.equals(allocationId, taskManagerSlot.getAssignedSlotRequest().getAllocationId())) {

				TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(taskManagerSlot.getInstanceId());

				if(taskManagerRegistration == null) {
					throw new IllegalStateException(
						"Trying to remove slot request from slot for which there is no TaskManager " + taskManagerSlot
							.getInstanceId() + " is registered.");
				}

				// clear the pending slot request
				taskManagerSlot.clearPendingSlotRequest();

				updateSlotState(taskManagerSlot, taskManagerRegistration, null, null);
			} else {
				LOG.debug("Ignore slot request removal for slot {}.", slotId);
			}
		} else {
			LOG.debug("There was no slot with {} registered. Probably this slot has been already freed.", slotId);
		}
	}

	/**
	 * Handles a failed slot request. The slot manager tries to find a new slot fulfilling
	 * the resource requirements for the failed slot request.
	 *
	 * @param slotId       identifying the slot which was assigned to the slot request before
	 * @param allocationId identifying the failed slot request
	 * @param cause        of the failure
	 */
	private void handleFailedSlotRequest(SlotID slotId, AllocationID allocationId, Throwable cause) {
		PendingSlotRequest pendingSlotRequest = pendingSlotRequests.get(allocationId);

		LOG.debug("Slot request with allocation id {} failed for slot {}.", allocationId, slotId, cause);

		if(null != pendingSlotRequest) {
			pendingSlotRequest.setRequestFuture(null);

			try {
				internalRequestSlot(pendingSlotRequest);
			} catch(ResourceManagerException e) {
				pendingSlotRequests.remove(allocationId);

				resourceActions.notifyAllocationFailure(pendingSlotRequest.getJobId(), allocationId, e);
			}
		} else {
			LOG.debug("There was not pending slot request with allocation id {}. Probably the request has been fulfilled or cancelled.",
				allocationId);
		}
	}

	/**
	 * Rejects the pending slot request by failing the request future with a
	 * {@link SlotAllocationException}.
	 *
	 * @param pendingSlotRequest to reject
	 * @param cause              of the rejection
	 */
	private void rejectPendingSlotRequest(PendingSlotRequest pendingSlotRequest, Exception cause) {
		CompletableFuture<Acknowledge> request = pendingSlotRequest.getRequestFuture();

		if(null != request) {
			request.completeExceptionally(new SlotAllocationException(cause));
		} else {
			LOG.debug("Cannot reject pending slot request {}, since no request has been sent.", pendingSlotRequest.getAllocationId());
		}
	}

	/**
	 * Cancels the given slot request.
	 *
	 * @param pendingSlotRequest to cancel
	 */
	private void cancelPendingSlotRequest(PendingSlotRequest pendingSlotRequest) {
		CompletableFuture<Acknowledge> request = pendingSlotRequest.getRequestFuture();

		returnPendingTaskManagerSlotIfAssigned(pendingSlotRequest);

		if(null != request) {
			// TODO_MA 注释： 取消
			request.cancel(false);
		}
	}

	@VisibleForTesting
	public static ResourceProfile generateDefaultSlotResourceProfile(WorkerResourceSpec workerResourceSpec, int numSlotsPerWorker) {
		return ResourceProfile.newBuilder().setCpuCores(workerResourceSpec.getCpuCores().divide(numSlotsPerWorker))
			.setTaskHeapMemory(workerResourceSpec.getTaskHeapSize().divide(numSlotsPerWorker))
			.setTaskOffHeapMemory(workerResourceSpec.getTaskOffHeapSize().divide(numSlotsPerWorker))
			.setManagedMemory(workerResourceSpec.getManagedMemSize().divide(numSlotsPerWorker))
			.setNetworkMemory(workerResourceSpec.getNetworkMemSize().divide(numSlotsPerWorker)).build();
	}

	// ---------------------------------------------------------------------------------------------
	// Internal timeout methods
	// ---------------------------------------------------------------------------------------------

	@VisibleForTesting
	void checkTaskManagerTimeouts() {

		// TODO_MA 注释： 先确保 taskManagerRegistrations 不为空，才有检测的必要性
		if(!taskManagerRegistrations.isEmpty()) {
			long currentTime = System.currentTimeMillis();

			// TODO_MA 注释： 初始化一个容器，用来存储 TimeOut 的 TaskManager
			ArrayList<TaskManagerRegistration> timedOutTaskManagers = new ArrayList<>(taskManagerRegistrations.size());

			// TODO_MA 注释： 遍历出来每个 TaskManager 进行超时检测
			// first retrieve the timed out TaskManagers
			for(TaskManagerRegistration taskManagerRegistration : taskManagerRegistrations.values()) {

				// TODO_MA 注释： 当处于 idle 状态超过： taskManagerTimeout
				if(currentTime - taskManagerRegistration.getIdleSince() >= taskManagerTimeout.toMilliseconds()) {
					// we collect the instance ids first in order to avoid concurrent modifications by the
					// ResourceActions.releaseResource call
					timedOutTaskManagers.add(taskManagerRegistration);
				}
			}

			// TODO_MA 注释： 释放 TaskTxecutor
			// second we trigger the release resource callback which can decide upon the resource release
			for(TaskManagerRegistration taskManagerRegistration : timedOutTaskManagers) {
				if(waitResultConsumedBeforeRelease) {
					releaseTaskExecutorIfPossible(taskManagerRegistration);
				} else {
					releaseTaskExecutor(taskManagerRegistration.getInstanceId());
				}
			}
		}
	}

	private void releaseTaskExecutorIfPossible(TaskManagerRegistration taskManagerRegistration) {
		long idleSince = taskManagerRegistration.getIdleSince();
		taskManagerRegistration.getTaskManagerConnection().getTaskExecutorGateway().canBeReleased().thenAcceptAsync(canBeReleased -> {
			InstanceID timedOutTaskManagerId = taskManagerRegistration.getInstanceId();
			boolean stillIdle = idleSince == taskManagerRegistration.getIdleSince();
			if(stillIdle && canBeReleased) {

				/*************************************************
				 *
				 *  注释： 释放 TaskExecutor
				 */
				releaseTaskExecutor(timedOutTaskManagerId);
			}
		}, mainThreadExecutor);
	}

	private void releaseTaskExecutor(InstanceID timedOutTaskManagerId) {
		final FlinkException cause = new FlinkException("TaskExecutor exceeded the idle timeout.");
		LOG.debug("Release TaskExecutor {} because it exceeded the idle timeout.", timedOutTaskManagerId);

		/*************************************************
		 *
		 *  注释： 释放资源
		 */
		resourceActions.releaseResource(timedOutTaskManagerId, cause);
	}

	private void checkSlotRequestTimeouts() {
		if(!pendingSlotRequests.isEmpty()) {
			long currentTime = System.currentTimeMillis();

			/*************************************************
			 *
			 *  注释： 获取到 PendingSlotRequest 的一个 迭代器
			 */
			Iterator<Map.Entry<AllocationID, PendingSlotRequest>> slotRequestIterator = pendingSlotRequests.entrySet().iterator();

			// TODO_MA 注释： 迭代
			while(slotRequestIterator.hasNext()) {

				// TODO_MA 注释： 拿到 PendingSlotRequest
				PendingSlotRequest slotRequest = slotRequestIterator.next().getValue();

				/*************************************************
				 *
				 *  注释： 判断是否超时
				 */
				if(currentTime - slotRequest.getCreationTimestamp() >= slotRequestTimeout.toMilliseconds()) {

					// TODO_MA 注释： 如果超时，则从迭代器中移除
					slotRequestIterator.remove();

					if(slotRequest.isAssigned()) {

						// TODO_MA 注释： 取消该请求
						cancelPendingSlotRequest(slotRequest);
					}

					/*************************************************
					 *
					 *  注释： 告知 ResourceManager
					 */
					resourceActions.notifyAllocationFailure(slotRequest.getJobId(), slotRequest.getAllocationId(),
						new TimeoutException("The allocation could not be fulfilled in time."));
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Internal utility methods
	// ---------------------------------------------------------------------------------------------

	private void internalUnregisterTaskManager(TaskManagerRegistration taskManagerRegistration, Exception cause) {
		Preconditions.checkNotNull(taskManagerRegistration);

		removeSlots(taskManagerRegistration.getSlots(), cause);
	}

	private boolean checkDuplicateRequest(AllocationID allocationId) {
		return pendingSlotRequests.containsKey(allocationId) || fulfilledSlotRequests.containsKey(allocationId);
	}

	private void checkInit() {
		Preconditions.checkState(started, "The slot manager has not been started.");
	}

	// ---------------------------------------------------------------------------------------------
	// Testing methods
	// ---------------------------------------------------------------------------------------------

	@VisibleForTesting
	TaskManagerSlot getSlot(SlotID slotId) {
		return slots.get(slotId);
	}

	@VisibleForTesting
	PendingSlotRequest getSlotRequest(AllocationID allocationId) {
		return pendingSlotRequests.get(allocationId);
	}

	@VisibleForTesting
	boolean isTaskManagerIdle(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if(null != taskManagerRegistration) {
			return taskManagerRegistration.isIdle();
		} else {
			return false;
		}
	}

	@Override
	@VisibleForTesting
	public void unregisterTaskManagersAndReleaseResources() {
		Iterator<Map.Entry<InstanceID, TaskManagerRegistration>> taskManagerRegistrationIterator = taskManagerRegistrations.entrySet()
			.iterator();

		while(taskManagerRegistrationIterator.hasNext()) {
			TaskManagerRegistration taskManagerRegistration = taskManagerRegistrationIterator.next().getValue();

			taskManagerRegistrationIterator.remove();

			final FlinkException cause = new FlinkException("Triggering of SlotManager#unregisterTaskManagersAndReleaseResources.");
			internalUnregisterTaskManager(taskManagerRegistration, cause);
			resourceActions.releaseResource(taskManagerRegistration.getInstanceId(), cause);
		}
	}
}
