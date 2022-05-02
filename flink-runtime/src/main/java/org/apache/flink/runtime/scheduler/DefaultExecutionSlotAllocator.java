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

package org.apache.flink.runtime.scheduler;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.SlotProfile;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.executiongraph.SlotProviderStrategy;
import org.apache.flink.runtime.instance.SlotSharingGroupId;
import org.apache.flink.runtime.jobmanager.scheduler.ScheduledUnit;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.jobmaster.SlotRequestId;
import org.apache.flink.runtime.jobmaster.slotpool.SlotProvider;
import org.apache.flink.runtime.scheduler.strategy.ExecutionVertexID;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.apache.flink.runtime.executiongraph.ExecutionVertex.MAX_DISTINCT_LOCATIONS_TO_CONSIDER;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Default {@link ExecutionSlotAllocator} which will use {@link SlotProvider} to allocate slots and
 * keep the unfulfilled requests for further cancellation.
 */
public class DefaultExecutionSlotAllocator implements ExecutionSlotAllocator {

	private static final Logger LOG = LoggerFactory.getLogger(DefaultExecutionSlotAllocator.class);

	/**
	 * Store the uncompleted slot assignments.
	 */
	private final Map<ExecutionVertexID, SlotExecutionVertexAssignment> pendingSlotAssignments;

	private final SlotProviderStrategy slotProviderStrategy;

	private final InputsLocationsRetriever inputsLocationsRetriever;

	public DefaultExecutionSlotAllocator(SlotProviderStrategy slotProviderStrategy, InputsLocationsRetriever inputsLocationsRetriever) {
		this.slotProviderStrategy = checkNotNull(slotProviderStrategy);
		this.inputsLocationsRetriever = checkNotNull(inputsLocationsRetriever);

		pendingSlotAssignments = new HashMap<>();
	}

	/*************************************************
	 *
	 *  注释： 三个步骤：
	 *  1、首先初始化一个容器，用来存储申请到的 SlotExecutionVertexAssignment
	 *  2、遍历待申请slot的 ExecutionVertex 集合： executionVertexSchedulingRequirements， 依次执行 slot 申请
	 *  3、处理申请结果： 如果申请到，最终申请到的是： LogicalSlot
	 * 	在申请 slot 过程中的两种关于 slot 的抽象：
	 * 	1、LogicalSlot	逻辑slot
	 * 	2、PhysicalSlot	物理slot
	 * 		共享 slot 的概念
	 * 		可能存在多个Task公用一个slot的情况：
	 * 			事实上，每个去申请slot的Task，都能申请到一个LogicalSlot
	 * 		    但是有可能，多个申请 slot 的 Task 申请到的 LogicalSlot 属于同一个 PhysicalSlot
	 */
	@Override
	public List<SlotExecutionVertexAssignment> allocateSlotsFor(
		List<ExecutionVertexSchedulingRequirements> executionVertexSchedulingRequirements) {

		validateSchedulingRequirements(executionVertexSchedulingRequirements);

		// TODO_MA 注释： 计算待申请的 Slot 的个数
		// TODO_MA 注释： ExecutionVertexSchedulingRequirements ==> SlotExecutionVertexAssignment
		List<SlotExecutionVertexAssignment> slotExecutionVertexAssignments = new ArrayList<>(executionVertexSchedulingRequirements.size());

		// TODO_MA 注释： 计算这些 Slot 申请过程中的么个 Slot 的 AllocationID
		Set<AllocationID> allPreviousAllocationIds = computeAllPriorAllocationIds(executionVertexSchedulingRequirements);

		/*************************************************
		 *
		 *  注释： 遍历每个 ExecutionVertexSchedulingRequirements， 为每个 ExecutionVertex 申请 Slot
		 */
		for(ExecutionVertexSchedulingRequirements schedulingRequirements : executionVertexSchedulingRequirements) {
			final ExecutionVertexID executionVertexId = schedulingRequirements.getExecutionVertexId();
			final SlotRequestId slotRequestId = new SlotRequestId();
			final SlotSharingGroupId slotSharingGroupId = schedulingRequirements.getSlotSharingGroupId();

			LOG.debug("Allocate slot with id {} for execution {}", slotRequestId, executionVertexId);

			/*************************************************
			 *
			 *  注释： calculatePreferredLocations 计算 slot 本地性
			 */
			CompletableFuture<LogicalSlot> slotFuture = calculatePreferredLocations(executionVertexId,
				schedulingRequirements.getPreferredLocations(), inputsLocationsRetriever)
				.thenCompose((Collection<TaskManagerLocation> preferredLocations) ->

					/*************************************************
					 *
					 *  注释： NormalSlotProviderStrategy
					 */
					slotProviderStrategy.allocateSlot(
						slotRequestId,
						new ScheduledUnit(executionVertexId, slotSharingGroupId, schedulingRequirements.getCoLocationConstraint()), SlotProfile
							.priorAllocation(schedulingRequirements.getTaskResourceProfile(),
								schedulingRequirements.getPhysicalSlotResourceProfile(), preferredLocations,
								Collections.singletonList(schedulingRequirements.getPreviousAllocationId()), allPreviousAllocationIds)));

			/*************************************************
			 *
			 *  注释： 将申请到的 slot 和 executionVertex 对应起来
			 */
			SlotExecutionVertexAssignment slotExecutionVertexAssignment = new SlotExecutionVertexAssignment(executionVertexId, slotFuture);
			// add to map first to avoid the future completed before added.
			pendingSlotAssignments.put(executionVertexId, slotExecutionVertexAssignment);

			slotFuture.whenComplete((ignored, throwable) -> {
				pendingSlotAssignments.remove(executionVertexId);
				if(throwable != null) {
					slotProviderStrategy.cancelSlotRequest(slotRequestId, slotSharingGroupId, throwable);
				}
			});

			/*************************************************
			 *
			 *  注释： 加入已申请到的 slot 抽象的集合中
			 */
			slotExecutionVertexAssignments.add(slotExecutionVertexAssignment);
		}

		// TODO_MA 注释： 返回申请到的 slot 的抽象集合
		return slotExecutionVertexAssignments;
	}

	private void validateSchedulingRequirements(Collection<ExecutionVertexSchedulingRequirements> schedulingRequirements) {
		schedulingRequirements.stream().map(ExecutionVertexSchedulingRequirements::getExecutionVertexId).forEach(
			id -> checkState(!pendingSlotAssignments.containsKey(id),
				"BUG: vertex %s tries to allocate a slot when its previous slot request is still pending", id));
	}

	@Override
	public void cancel(ExecutionVertexID executionVertexId) {
		SlotExecutionVertexAssignment slotExecutionVertexAssignment = pendingSlotAssignments.get(executionVertexId);
		if(slotExecutionVertexAssignment != null) {
			slotExecutionVertexAssignment.getLogicalSlotFuture().cancel(false);
		}
	}

	@Override
	public CompletableFuture<Void> stop() {
		List<ExecutionVertexID> executionVertexIds = new ArrayList<>(pendingSlotAssignments.keySet());
		executionVertexIds.forEach(this::cancel);

		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Calculates the preferred locations for an execution.
	 * It will first try to use preferred locations based on state,
	 * if null, will use the preferred locations based on inputs.
	 */
	private static CompletableFuture<Collection<TaskManagerLocation>> calculatePreferredLocations(ExecutionVertexID executionVertexId,
		Collection<TaskManagerLocation> preferredLocationsBasedOnState, InputsLocationsRetriever inputsLocationsRetriever) {

		if(!preferredLocationsBasedOnState.isEmpty()) {
			return CompletableFuture.completedFuture(preferredLocationsBasedOnState);
		}

		return getPreferredLocationsBasedOnInputs(executionVertexId, inputsLocationsRetriever);
	}

	/**
	 * Gets the location preferences of the execution, as determined by the locations
	 * of the predecessors from which it receives input data.
	 * If there are more than {@link ExecutionVertex#MAX_DISTINCT_LOCATIONS_TO_CONSIDER} different locations of source data,
	 * or neither the sources have not been started nor will be started with the execution together,
	 * this method returns an empty collection to indicate no location preference.
	 *
	 * @return The preferred locations based in input streams, or an empty iterable,
	 * if there is no input-based preference.
	 */
	@VisibleForTesting
	static CompletableFuture<Collection<TaskManagerLocation>> getPreferredLocationsBasedOnInputs(ExecutionVertexID executionVertexId,
		InputsLocationsRetriever inputsLocationsRetriever) {
		CompletableFuture<Collection<TaskManagerLocation>> preferredLocations = CompletableFuture.completedFuture(Collections.emptyList());

		Collection<CompletableFuture<TaskManagerLocation>> locationsFutures = new ArrayList<>();

		Collection<Collection<ExecutionVertexID>> allProducers = inputsLocationsRetriever
			.getConsumedResultPartitionsProducers(executionVertexId);
		for(Collection<ExecutionVertexID> producers : allProducers) {

			for(ExecutionVertexID producer : producers) {
				Optional<CompletableFuture<TaskManagerLocation>> optionalLocationFuture = inputsLocationsRetriever
					.getTaskManagerLocation(producer);
				optionalLocationFuture.ifPresent(locationsFutures::add);
				// If the parallelism is large, wait for all futures coming back may cost a long time.
				if(locationsFutures.size() > MAX_DISTINCT_LOCATIONS_TO_CONSIDER) {
					locationsFutures.clear();
					break;
				}
			}

			CompletableFuture<Collection<TaskManagerLocation>> uniqueLocationsFuture = FutureUtils.combineAll(locationsFutures)
				.thenApply(HashSet::new);
			preferredLocations = preferredLocations.thenCombine(uniqueLocationsFuture, (locationsOnOneEdge, locationsOnAnotherEdge) -> {
				if((!locationsOnOneEdge.isEmpty() && locationsOnAnotherEdge.size() > locationsOnOneEdge.size()) || locationsOnAnotherEdge
					.isEmpty()) {
					return locationsOnOneEdge;
				} else {
					return locationsOnAnotherEdge;
				}
			});
			locationsFutures.clear();
		}
		return preferredLocations;
	}

	/**
	 * Computes and returns a set with the prior allocation ids from all execution vertices scheduled together.
	 *
	 * @param executionVertexSchedulingRequirements contains the execution vertices which are scheduled together
	 */
	@VisibleForTesting
	static Set<AllocationID> computeAllPriorAllocationIds(
		Collection<ExecutionVertexSchedulingRequirements> executionVertexSchedulingRequirements) {

		/*************************************************
		 *
		 *  注释： 生成 AllocationID
		 */
		return executionVertexSchedulingRequirements.stream().map(ExecutionVertexSchedulingRequirements::getPreviousAllocationId)
			.filter(Objects::nonNull).collect(Collectors.toSet());
	}

	@VisibleForTesting
	int getNumberOfPendingSlotAssignments() {
		return pendingSlotAssignments.size();
	}
}
