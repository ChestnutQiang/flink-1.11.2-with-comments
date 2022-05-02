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

package org.apache.flink.runtime.jobmaster.slotpool;

import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.jobmanager.slots.TaskManagerGateway;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;

import java.util.concurrent.atomic.AtomicReference;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * // TODO_MA 注释： PhysicalSlot 表征的是物理意义上 TaskExecutor 上的一个 slot，
 * // TODO_MA 注释： 而 LogicalSlot 表征逻辑上的一个 slot，一个 task 可以部署到一个 LogicalSlot 上，
 * // TODO_MA 注释： 但它和物理上一个具体的 slot 并不是一一对应的。
 * // TODO_MA 注释： 由于资源共享等机制的存在，多个 LogicalSlot 可能被映射到同一个 PhysicalSlot 上。
 *
 * // TODO_MA 注释： {@code AllocatedSlot} 表示 JobMaster 从 TaskExecutor 分配的插槽。
 * // TODO_MA 注释： 它代表TaskExecutor分配的资源的一部分。
 * The {@code AllocatedSlot} represents a slot that the JobMaster allocated from a TaskExecutor.
 * It represents a slice of allocated resources from the TaskExecutor.
 *
 * <p>To allocate an {@code AllocatedSlot}, the requests a slot from the ResourceManager. The
 * ResourceManager picks (or starts) a TaskExecutor that will then allocate the slot to the
 * JobMaster and notify the JobMaster.
 *
 * // TODO_MA 注释： 要分配 {@code AllocatedSlot}，请从 ResourceManager 请求一个插槽。
 * // TODO_MA 注释： ResourceManager 选择（或启动）TaskExecutor，
 * // TODO_MA 注释： 该 TaskExecutor 然后将插槽分配给 JobMaster 并通知 JobMaster。
 * <p>Note: Prior to the resource management changes introduced in (Flink Improvement Proposal 6),
 * an AllocatedSlot was allocated to the JobManager as soon as the TaskManager registered at the
 * JobManager. All slots had a default unknown resource profile.
 */
class AllocatedSlot implements PhysicalSlot {

	/**
	 * The ID under which the slot is allocated. Uniquely identifies the slot.
	 */
	private final AllocationID allocationId;

	/**
	 * The location information of the TaskManager to which this slot belongs
	 */
	private final TaskManagerLocation taskManagerLocation;

	/**
	 * The resource profile of the slot provides
	 */
	private final ResourceProfile resourceProfile;

	/**
	 * RPC gateway to call the TaskManager that holds this slot
	 */
	private final TaskManagerGateway taskManagerGateway;

	/**
	 * The number of the slot on the TaskManager to which slot belongs. Purely informational.
	 */
	private final int physicalSlotNumber;

	private final AtomicReference<Payload> payloadReference;

	// ------------------------------------------------------------------------

	public AllocatedSlot(AllocationID allocationId, TaskManagerLocation location, int physicalSlotNumber, ResourceProfile resourceProfile,
		TaskManagerGateway taskManagerGateway) {
		this.allocationId = checkNotNull(allocationId);
		this.taskManagerLocation = checkNotNull(location);
		this.physicalSlotNumber = physicalSlotNumber;
		this.resourceProfile = checkNotNull(resourceProfile);
		this.taskManagerGateway = checkNotNull(taskManagerGateway);

		payloadReference = new AtomicReference<>(null);
	}

	// ------------------------------------------------------------------------

	/**
	 * Gets the Slot's unique ID defined by its TaskManager.
	 */
	public SlotID getSlotId() {
		return new SlotID(getTaskManagerId(), physicalSlotNumber);
	}

	@Override
	public AllocationID getAllocationId() {
		return allocationId;
	}

	/**
	 * Gets the ID of the TaskManager on which this slot was allocated.
	 *
	 * <p>This is equivalent to {@link #getTaskManagerLocation()#getTaskManagerId()}.
	 *
	 * @return This slot's TaskManager's ID.
	 */
	public ResourceID getTaskManagerId() {
		return getTaskManagerLocation().getResourceID();
	}

	@Override
	public ResourceProfile getResourceProfile() {
		return resourceProfile;
	}

	@Override
	public TaskManagerLocation getTaskManagerLocation() {
		return taskManagerLocation;
	}

	@Override
	public TaskManagerGateway getTaskManagerGateway() {
		return taskManagerGateway;
	}

	@Override
	public int getPhysicalSlotNumber() {
		return physicalSlotNumber;
	}

	/**
	 * Returns true if this slot is being used (e.g. a logical slot is allocated from this slot).
	 *
	 * @return true if a logical slot is allocated from this slot, otherwise false
	 */
	public boolean isUsed() {
		return payloadReference.get() != null;
	}

	@Override
	public boolean tryAssignPayload(Payload payload) {
		return payloadReference.compareAndSet(null, payload);
	}

	/**
	 * Triggers the release of the assigned payload. If the payload could be released,
	 * then it is removed from the slot.
	 *
	 * @param cause of the release operation
	 */
	public void releasePayload(Throwable cause) {
		final Payload payload = payloadReference.get();

		if(payload != null) {
			payload.release(cause);
			payloadReference.set(null);
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * This always returns a reference hash code.
	 */
	@Override
	public final int hashCode() {
		return super.hashCode();
	}

	/**
	 * This always checks based on reference equality.
	 */
	@Override
	public final boolean equals(Object obj) {
		return this == obj;
	}

	@Override
	public String toString() {
		return "AllocatedSlot " + allocationId + " @ " + taskManagerLocation + " - " + physicalSlotNumber;
	}
}
