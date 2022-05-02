/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.io.PullingAsyncDataInput;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * The {@link CheckpointedInputGate} uses {@link CheckpointBarrierHandler} to handle incoming
 * {@link CheckpointBarrier} from the {@link InputGate}.
 */
@Internal
public class CheckpointedInputGate implements PullingAsyncDataInput<BufferOrEvent>, Closeable {
	private final CheckpointBarrierHandler barrierHandler;

	/**
	 * The gate that the buffer draws its input from.
	 */
	private final InputGate inputGate;

	/**
	 * Indicate end of the input.
	 */
	private boolean isFinished;

	/**
	 * Creates a new checkpoint stream aligner.
	 *
	 * <p>The aligner will allow only alignments that buffer up to the given number of bytes.
	 * When that number is exceeded, it will stop the alignment and notify the task that the
	 * checkpoint has been cancelled.
	 *
	 * @param inputGate      The input gate to draw the buffers and events from.
	 * @param barrierHandler Handler that controls which channels are blocked.
	 */
	public CheckpointedInputGate(InputGate inputGate, CheckpointBarrierHandler barrierHandler) {
		this.inputGate = inputGate;
		this.barrierHandler = barrierHandler;
	}

	@Override
	public CompletableFuture<?> getAvailableFuture() {
		return inputGate.getAvailableFuture();
	}

	/*************************************************
	 *
	 *  注释：
	 *  一个Task的执行有输入和输出：
	 *  1、输入： inputGate
	 *  2、输出： resultPartitioin
	 */
	@Override
	public Optional<BufferOrEvent> pollNext() throws Exception {
		while(true) {

			// TODO_MA 注释： 从缓冲区或者 InputGate 中拉取数据
			// TODO_MA 注释： inputGate = UnionInputGate
			Optional<BufferOrEvent> next = inputGate.pollNext();

			// TODO_MA 注释： 如果当前缓冲区为空，则从 InputGate 获取数据
			if(!next.isPresent()) {
				return handleEmptyBuffer();
			}

			BufferOrEvent bufferOrEvent = next.get();
			// TODO_MA 注释： 如果一个 channel 阻塞了，说明还有其他 channel barrier 没有到来，把阻塞的 channel 元素保存在 bufferStorage
			checkState(!barrierHandler.isBlocked(bufferOrEvent.getChannelInfo()));

			// TODO_MA 注释： 如果是 Buffer，直接返回，交给 operator 处理
			if(bufferOrEvent.isBuffer()) {
				return next;
			}

			// TODO_MA 注释： CheckpointBarrier 交给 barrierHandler 处理
			else if(bufferOrEvent.getEvent().getClass() == CheckpointBarrier.class) {
				CheckpointBarrier checkpointBarrier = (CheckpointBarrier) bufferOrEvent.getEvent();

				/*************************************************
				 *
				 *  注释： 关于 barrierHandler， 有两种实现：
				 *  1、CheckpointBarrierAligner 对齐 ===> exactly once
				 *  2、CheckpointBarrierTracker 追踪 ====> at least once
				 */
				barrierHandler.processBarrier(checkpointBarrier, bufferOrEvent.getChannelInfo());
				return next;
			}

			// TODO_MA 注释： 处理 CancellationBarrier
			else if(bufferOrEvent.getEvent().getClass() == CancelCheckpointMarker.class) {
				barrierHandler.processCancellationBarrier((CancelCheckpointMarker) bufferOrEvent.getEvent());
			}

			// TODO_MA 注释：
			else {
				if(bufferOrEvent.getEvent().getClass() == EndOfPartitionEvent.class) {
					barrierHandler.processEndOfPartition();
				}
				return next;
			}
		}
	}

	public void spillInflightBuffers(long checkpointId, int channelIndex, ChannelStateWriter channelStateWriter) throws IOException {
		InputChannel channel = inputGate.getChannel(channelIndex);
		if(barrierHandler.hasInflightData(checkpointId, channel.getChannelInfo())) {
			channel.spillInflightBuffers(checkpointId, channelStateWriter);
		}
	}

	public CompletableFuture<Void> getAllBarriersReceivedFuture(long checkpointId) {
		return barrierHandler.getAllBarriersReceivedFuture(checkpointId);
	}

	private Optional<BufferOrEvent> handleEmptyBuffer() {
		if(inputGate.isFinished()) {
			isFinished = true;
		}

		return Optional.empty();
	}

	@Override
	public boolean isFinished() {
		return isFinished;
	}

	/**
	 * Cleans up all internally held resources.
	 *
	 * @throws IOException Thrown if the cleanup of I/O resources failed.
	 */
	public void close() throws IOException {
		barrierHandler.close();
	}

	// ------------------------------------------------------------------------
	//  Properties
	// ------------------------------------------------------------------------

	/**
	 * Gets the ID defining the current pending, or just completed, checkpoint.
	 *
	 * @return The ID of the pending of completed checkpoint.
	 */
	@VisibleForTesting
	long getLatestCheckpointId() {
		return barrierHandler.getLatestCheckpointId();
	}

	/**
	 * Gets the time that the latest alignment took, in nanoseconds.
	 * If there is currently an alignment in progress, it will return the time spent in the
	 * current alignment so far.
	 *
	 * @return The duration in nanoseconds
	 */
	@VisibleForTesting
	long getAlignmentDurationNanos() {
		return barrierHandler.getAlignmentDurationNanos();
	}

	/**
	 * @return the time that elapsed, in nanoseconds, between the creation of the latest checkpoint
	 * and the time when it's first {@link CheckpointBarrier} was received by this {@link InputGate}.
	 */
	@VisibleForTesting
	long getCheckpointStartDelayNanos() {
		return barrierHandler.getCheckpointStartDelayNanos();
	}

	/**
	 * @return number of underlying input channels.
	 */
	public int getNumberOfInputChannels() {
		return inputGate.getNumberOfInputChannels();
	}

	// ------------------------------------------------------------------------
	// Utilities
	// ------------------------------------------------------------------------

	@Override
	public String toString() {
		return barrierHandler.toString();
	}

	public InputChannel getChannel(int channelIndex) {
		return inputGate.getChannel(channelIndex);
	}

	public List<InputChannelInfo> getChannelInfos() {
		return inputGate.getChannelInfos();
	}

	@VisibleForTesting
	CheckpointBarrierHandler getCheckpointBarrierHandler() {
		return barrierHandler;
	}
}
