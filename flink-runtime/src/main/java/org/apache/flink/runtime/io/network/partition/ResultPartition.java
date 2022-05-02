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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;
import org.apache.flink.runtime.io.network.buffer.BufferCompressor;
import org.apache.flink.runtime.io.network.buffer.BufferConsumer;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.BufferPoolOwner;
import org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.taskexecutor.TaskExecutor;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.FunctionWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkElementIndex;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * // TODO_MA 注释： 一个单个任务产生的数据的结果分区
 * A result partition for data produced by a single task.
 *
 * // TODO_MA 注释： 此类是逻辑 {@link IntermediateResultPartition} 的运行时部分。本质上， 结果分区是{@link Buffer}实例的集合。
 * // TODO_MA 注释： 缓冲区被组织在一个或多个 {@link ResultSubpartition} 实例中，
 * // TODO_MA 注释： 这些实例进一步根据消耗任务的数量和数据 {@link DistributionPattern} 对数据进行分区。
 * <p>This class is the runtime part of a logical {@link IntermediateResultPartition}. Essentially,
 * a result partition is a collection of {@link Buffer} instances. The buffers are organized in one
 * or more {@link ResultSubpartition} instances, which further partition the data depending on the
 * number of consuming tasks and the data {@link DistributionPattern}.
 *
 * // TODO_MA 注释： consume 结果分区的 Task 必须请求其子分区之一。
 * // TODO_MA 注释： 请求远程（请参见{@link RemoteInputChannel}）或本地（请参见{@link LocalInputChannel}）发生
 * <p>Tasks, which consume a result partition have to request one of its subpartitions. The request
 * happens either remotely (see {@link RemoteInputChannel}) or locally (see {@link LocalInputChannel})
 *
 * <h2>Life-cycle</h2>
 *
 * // TODO_MA 注释： 每个结果分区的生命周期都有三个（可能是重叠的）阶段： Produce Consume Release
 * <p>The life-cycle of each result partition has three (possibly overlapping) phases:
 * <ol>
 * <li><strong>Produce</strong>: </li>
 * <li><strong>Consume</strong>: </li>
 * <li><strong>Release</strong>: </li>
 * </ol>
 * <h2>Buffer management</h2>
 * <h2>State management</h2>
 *
 * // TODO_MA 注释： 代表由一个 Task 的生成的数据，和 ExecutionGraph 中的 IntermediateResultPartition 一一对应
 * // TODO_MA 注释： 一个 ResultPartition 和 一个 ResultPartitionWriter 关联
 */
public class ResultPartition implements ResultPartitionWriter, BufferPoolOwner {

	protected static final Logger LOG = LoggerFactory.getLogger(ResultPartition.class);

	private final String owningTaskName;

	private final int partitionIndex;

	protected final ResultPartitionID partitionId;

	/**
	 * Type of this partition. Defines the concrete subpartition implementation to use.
	 */
	protected final ResultPartitionType partitionType;

	/**
	 * The subpartitions of this partition. At least one.
	 */
	/*************************************************
	 *
	 *  注释：  是 ResultPartition 的一个子分区。每个 ResultPartition 包含多个 ResultSubpartition，
	 *  其数目要由下游消费 Task 数和 DistributionPattern 来决定
	 *  例如，如果是 FORWARD，则下游只有一个消费者；如果是 SHUFFLE，则下游消费者的数量和下游算子的并行度一样
	 */
	protected final ResultSubpartition[] subpartitions;

	/*************************************************
	 *
	 *  注释： 跟踪管理 ResultPartition 的存在于 TaskManager 之上的一个管理器
	 *  ResultPartitionManager 管理当前 TaskManager 所有的 ResultPartition
	 */
	protected final ResultPartitionManager partitionManager;

	public final int numTargetKeyGroups;

	// - Runtime state --------------------------------------------------------

	private final AtomicBoolean isReleased = new AtomicBoolean();

	private BufferPool bufferPool;

	private boolean isFinished;

	private volatile Throwable cause;

	private final FunctionWithException<BufferPoolOwner, BufferPool, IOException> bufferPoolFactory;

	/**
	 * Used to compress buffer to reduce IO.
	 */
	@Nullable
	protected final BufferCompressor bufferCompressor;

	public ResultPartition(String owningTaskName, int partitionIndex, ResultPartitionID partitionId, ResultPartitionType partitionType,
		ResultSubpartition[] subpartitions, int numTargetKeyGroups, ResultPartitionManager partitionManager, @Nullable BufferCompressor bufferCompressor,
		FunctionWithException<BufferPoolOwner, BufferPool, IOException> bufferPoolFactory) {

		this.owningTaskName = checkNotNull(owningTaskName);
		Preconditions.checkArgument(0 <= partitionIndex, "The partition index must be positive.");
		this.partitionIndex = partitionIndex;
		this.partitionId = checkNotNull(partitionId);
		this.partitionType = checkNotNull(partitionType);
		this.subpartitions = checkNotNull(subpartitions);
		this.numTargetKeyGroups = numTargetKeyGroups;
		this.partitionManager = checkNotNull(partitionManager);
		this.bufferCompressor = bufferCompressor;
		this.bufferPoolFactory = bufferPoolFactory;
	}

	/**
	 * Registers a buffer pool with this result partition.
	 *
	 * <p>There is one pool for each result partition, which is shared by all its sub partitions.
	 *
	 * <p>The pool is registered with the partition *after* it as been constructed in order to conform
	 * to the life-cycle of task registrations in the {@link TaskExecutor}.
	 */
	@Override
	public void setup() throws IOException {
		checkState(this.bufferPool == null, "Bug in result partition setup logic: Already registered buffer pool.");

		BufferPool bufferPool = checkNotNull(bufferPoolFactory.apply(this));

		// TODO_MA 注释： MemorySegment > Subpartition
		checkArgument(bufferPool.getNumberOfRequiredMemorySegments() >= getNumberOfSubpartitions(),
			"Bug in result partition setup logic: Buffer pool has not enough guaranteed buffers for this result partition.");

		this.bufferPool = bufferPool;

		/*************************************************
		 *
		 *  注释： 注册 ResultPartition 启动好了之后，会注册在 ResultPartitionWriter 中
		 *  partitionManager 负责这个 Task 之上的所有的数据的输出
		 *  当前这个 Task 输出的所有数据就被抽象成一个整体： ResultPartition
		 *  这个Task输出的数据有可能要被分发到下游的多个Task，就证明产出多个分区： ResultSubpartition
		 *  ResultPartition 包含多个 ResultSubpartition
		 *  -
		 *  TaskManager - TaskExecutor  - ResultPartitionManager（管理多个 ResultPartition）
		 */
		partitionManager.registerResultPartition(this);
	}

	@Override
	public void readRecoveredState(ChannelStateReader stateReader) throws IOException, InterruptedException {

		// TODO_MA 注释： 遍历每个 ResultSubpartition 恢复状态
		for(ResultSubpartition subpartition : subpartitions) {

			/*************************************************
			 *
			 *  注释： 恢复 ResultSubpartition 的状态
			 */
			subpartition.readRecoveredState(stateReader);
		}
		LOG.debug("{}: Finished reading recovered state.", this);
	}

	public String getOwningTaskName() {
		return owningTaskName;
	}

	public ResultPartitionID getPartitionId() {
		return partitionId;
	}

	public int getPartitionIndex() {
		return partitionIndex;
	}

	@Override
	public ResultSubpartition getSubpartition(int subpartitionIndex) {
		return subpartitions[subpartitionIndex];
	}

	@Override
	public int getNumberOfSubpartitions() {
		return subpartitions.length;
	}

	public BufferPool getBufferPool() {
		return bufferPool;
	}

	public int getNumberOfQueuedBuffers() {
		int totalBuffers = 0;

		for(ResultSubpartition subpartition : subpartitions) {
			totalBuffers += subpartition.unsynchronizedGetNumberOfQueuedBuffers();
		}

		return totalBuffers;
	}

	/**
	 * Returns the type of this result partition.
	 *
	 * @return result partition type
	 */
	public ResultPartitionType getPartitionType() {
		return partitionType;
	}

	// ------------------------------------------------------------------------

	@Override
	public BufferBuilder getBufferBuilder(int targetChannel) throws IOException, InterruptedException {
		checkInProduceState();

		return bufferPool.requestBufferBuilderBlocking(targetChannel);
	}

	@Override
	public BufferBuilder tryGetBufferBuilder(int targetChannel) throws IOException {

		/*************************************************
		 *
		 *  注释：
		 */
		BufferBuilder bufferBuilder = bufferPool.requestBufferBuilder(targetChannel);
		return bufferBuilder;
	}

	@Override
	public boolean addBufferConsumer(BufferConsumer bufferConsumer, int subpartitionIndex, boolean isPriorityEvent) throws IOException {
		checkNotNull(bufferConsumer);

		ResultSubpartition subpartition;
		try {
			checkInProduceState();
			subpartition = subpartitions[subpartitionIndex];
		} catch(Exception ex) {
			bufferConsumer.close();
			throw ex;
		}

		// TODO_MA 注释： 添加 BufferConsumer，说明已经有数据生成了
		return subpartition.add(bufferConsumer, isPriorityEvent);
	}

	@Override
	public void flushAll() {

		// TODO_MA 注释： 所有 ReesultSubPartition 全部 flush
		for(ResultSubpartition subpartition : subpartitions) {
			subpartition.flush();
		}
	}

	@Override
	public void flush(int subpartitionIndex) {

		/*************************************************
		 *
		 *  注释： 刷新到对应 inputChannel 的 ResultSubPartition 中
		 *  ResultSubPartition = subpartitions[subpartitionIndex]
		 */
		subpartitions[subpartitionIndex].flush();
	}

	/**
	 * Finishes the result partition.
	 *
	 * <p>After this operation, it is not possible to add further data to the result partition.
	 *
	 * <p>For BLOCKING results, this will trigger the deployment of consuming tasks.
	 */
	@Override
	public void finish() throws IOException {
		checkInProduceState();

		/*************************************************
		 *
		 *  注释： 该 ResultPartition 下的每个 ResultSubpartition 都要去执行 Finish
		 */
		for(ResultSubpartition subpartition : subpartitions) {
			subpartition.finish();
		}

		isFinished = true;
	}

	public void release() {
		release(null);
	}

	/**
	 * Releases the result partition.
	 */
	public void release(Throwable cause) {
		if(isReleased.compareAndSet(false, true)) {
			LOG.debug("{}: Releasing {}.", owningTaskName, this);

			// Set the error cause
			if(cause != null) {
				this.cause = cause;
			}

			// Release all subpartitions
			for(ResultSubpartition subpartition : subpartitions) {
				try {
					subpartition.release();
				}
				// Catch this in order to ensure that release is called on all subpartitions
				catch(Throwable t) {
					LOG.error("Error during release of result subpartition: " + t.getMessage(), t);
				}
			}
		}
	}

	@Override
	public void close() {
		if(bufferPool != null) {
			bufferPool.lazyDestroy();
		}
	}

	@Override
	public void fail(@Nullable Throwable throwable) {
		partitionManager.releasePartition(partitionId, throwable);
	}

	/**
	 * // TODO_MA 注释： 在指定的 ResultSubpartition 中创建一个 ResultSubpartitionView，用于消费数据
	 * Returns the requested subpartition.
	 */
	public ResultSubpartitionView createSubpartitionView(int index, BufferAvailabilityListener availabilityListener) throws IOException {
		checkElementIndex(index, subpartitions.length, "Subpartition not found.");
		checkState(!isReleased.get(), "Partition released.");

		// TODO_MA 注释： 创建 ResultSubpartitionView，可以看作是 ResultSubpartition 的消费者
		ResultSubpartitionView readView = subpartitions[index].createReadView(availabilityListener);

		LOG.debug("Created {}", readView);

		return readView;
	}

	public Throwable getFailureCause() {
		return cause;
	}

	@Override
	public int getNumTargetKeyGroups() {
		return numTargetKeyGroups;
	}

	/**
	 * Releases buffers held by this result partition.
	 *
	 * <p>This is a callback from the buffer pool, which is registered for result partitions, which
	 * are back pressure-free.
	 */
	@Override
	public void releaseMemory(int toRelease) throws IOException {
		checkArgument(toRelease > 0);

		for(ResultSubpartition subpartition : subpartitions) {
			toRelease -= subpartition.releaseMemory();

			// Only release as much memory as needed
			if(toRelease <= 0) {
				break;
			}
		}
	}

	/**
	 * Whether this partition is released.
	 *
	 * <p>A partition is released when each subpartition is either consumed and communication is closed by consumer
	 * or failed. A partition is also released if task is cancelled.
	 */
	public boolean isReleased() {
		return isReleased.get();
	}

	@Override
	public CompletableFuture<?> getAvailableFuture() {
		return bufferPool.getAvailableFuture();
	}

	@Override
	public String toString() {
		return "ResultPartition " + partitionId.toString() + " [" + partitionType + ", " + subpartitions.length + " subpartitions]";
	}

	// ------------------------------------------------------------------------

	/**
	 * Notification when a subpartition is released.
	 */
	void onConsumedSubpartition(int subpartitionIndex) {

		if(isReleased.get()) {
			return;
		}

		LOG.debug("{}: Received release notification for subpartition {}.", this, subpartitionIndex);
	}

	public ResultSubpartition[] getAllPartitions() {
		return subpartitions;
	}

	// ------------------------------------------------------------------------

	private void checkInProduceState() throws IllegalStateException {
		checkState(!isFinished, "Partition already finished.");
	}
}
