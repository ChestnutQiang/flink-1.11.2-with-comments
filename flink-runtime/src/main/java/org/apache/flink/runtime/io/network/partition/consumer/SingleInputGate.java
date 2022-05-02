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

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.core.memory.MemorySegmentProvider;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferDecompressor;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.buffer.BufferReceivedListener;
import org.apache.flink.runtime.io.network.partition.PartitionProducerStateProvider;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel.BufferAndAvailability;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.shuffle.NettyShuffleDescriptor;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.SupplierWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * // TODO_MA 注释： 输入门消耗单个产生的中间结果的一个或多个分区。
 * An input gate consumes one or more partitions of a single produced intermediate result.
 *
 * <p>Each intermediate result is partitioned over its producing parallel subtasks; each of these
 * partitions is furthermore partitioned into one or more subpartitions.
 *
 * <p>As an example, consider a map-reduce program, where the map operator produces data and the
 * reduce operator consumes the produced data.
 *
 * <pre>{@code
 * +-----+              +---------------------+              +--------+
 * | Map | = produce => | Intermediate Result | <= consume = | Reduce |
 * +-----+              +---------------------+              +--------+
 * }</pre>
 *
 * <p>When deploying such a program in parallel, the intermediate result will be partitioned over its
 * producing parallel subtasks; each of these partitions is furthermore partitioned into one or more
 * subpartitions.
 *
 * <pre>{@code
 *                            Intermediate result
 *               +-----------------------------------------+
 *               |                      +----------------+ |              +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 1 | | <=======+=== | Input Gate | Reduce 1 |
 * | Map 1 | ==> | | Partition 1 | =|   +----------------+ |         |    +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 2 | | <==+    |
 *               |                      +----------------+ |    |    | Subpartition request
 *               |                                         |    |    |
 *               |                      +----------------+ |    |    |
 * +-------+     | +-------------+  +=> | Subpartition 1 | | <==+====+
 * | Map 2 | ==> | | Partition 2 | =|   +----------------+ |    |         +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 2 | | <==+======== | Input Gate | Reduce 2 |
 *               |                      +----------------+ |              +-----------------------+
 *               +-----------------------------------------+
 * }</pre>
 *
 * <p>In the above example, two map subtasks produce the intermediate result in parallel, resulting
 * in two partitions (Partition 1 and 2). Each of these partitions is further partitioned into two
 * subpartitions -- one for each parallel reduce subtask.
 *
 * // TODO_MA 注释： 通过内部维护的一个队列形成一个生产者-消费者的模型，当 InputChannel 中有数据时就加入到队列中，
 * // TODO_MA 注释： 在需要获取数据时从队列中取出一个 channel，获取 channel 中的数据。
 */
public class SingleInputGate extends IndexedInputGate {

	private static final Logger LOG = LoggerFactory.getLogger(SingleInputGate.class);

	/**
	 * Lock object to guard partition requests and runtime channel updates.
	 */
	private final Object requestLock = new Object();

	/**
	 * The name of the owning task, for logging purposes.
	 */
	private final String owningTaskName;

	private final int gateIndex;

	/**
	 * The ID of the consumed intermediate result. Each input gate consumes partitions of the
	 * intermediate result specified by this ID. This ID also identifies the input gate at the
	 * consuming task.
	 */
	private final IntermediateDataSetID consumedResultId;

	/**
	 * The type of the partition the input gate is consuming.
	 */
	private final ResultPartitionType consumedPartitionType;

	/**
	 * The index of the consumed subpartition of each consumed partition. This index depends on the
	 * {@link DistributionPattern} and the subtask indices of the producing and consuming task.
	 */
	private final int consumedSubpartitionIndex;

	/**
	 * The number of input channels (equivalent to the number of consumed partitions).
	 */
	private final int numberOfInputChannels;

	/**
	 * // TODO_MA 注释： 该 InputGate 包含的所有 InputChannel
	 * // TODO_MA 注释： 一个 InputChannel 对应到一个 IntermediateResultPartition， 对应到一个 ResultSubpartition
	 * Input channels. There is a one input channel for each consumed intermediate result partition.
	 * We store this in a map for runtime updates of single channels.
	 */
	private final Map<IntermediateResultPartitionID, InputChannel> inputChannels;

	/*************************************************
	 * 
	 *  注释： 输入 Channel 集合
	 */
	@GuardedBy("requestLock")
	private final InputChannel[] channels;

	// TODO_MA 注释： InputChannel 构成的队列，这些 InputChannel 中都有有可供消费的数据
	/**
	 * Channels, which notified this input gate about available data.
	 */
	private final ArrayDeque<InputChannel> inputChannelsWithData = new ArrayDeque<>();

	/**
	 * Field guaranteeing uniqueness for inputChannelsWithData queue. Both of those fields should be unified onto one.
	 */
	private final BitSet enqueuedInputChannelsWithData;

	private final BitSet channelsWithEndOfPartitionEvents;

	/**
	 * The partition producer state listener.
	 */
	private final PartitionProducerStateProvider partitionProducerStateProvider;

	/**
	 * Buffer pool for incoming buffers. Incoming data from remote channels is copied to buffers from this pool.
	 * // TODO_MA 注释： 用于接收输入的缓冲池
	 */
	private BufferPool bufferPool;

	private boolean hasReceivedAllEndOfPartitionEvents;

	/**
	 * Flag indicating whether partitions have been requested.
	 */
	private boolean requestedPartitionsFlag;

	private final List<TaskEvent> pendingEvents = new ArrayList<>();

	private int numberOfUninitializedChannels;

	/**
	 * A timer to retrigger local partition requests. Only initialized if actually needed.
	 */
	private Timer retriggerLocalRequestTimer;

	private final SupplierWithException<BufferPool, IOException> bufferPoolFactory;

	private final CompletableFuture<Void> closeFuture;

	@Nullable
	private volatile BufferReceivedListener bufferReceivedListener;

	@Nullable
	private final BufferDecompressor bufferDecompressor;

	private final MemorySegmentProvider memorySegmentProvider;

	public SingleInputGate(String owningTaskName, int gateIndex, IntermediateDataSetID consumedResultId,
		final ResultPartitionType consumedPartitionType, int consumedSubpartitionIndex, int numberOfInputChannels,
		PartitionProducerStateProvider partitionProducerStateProvider, SupplierWithException<BufferPool, IOException> bufferPoolFactory,
		@Nullable BufferDecompressor bufferDecompressor, MemorySegmentProvider memorySegmentProvider) {

		this.owningTaskName = checkNotNull(owningTaskName);
		Preconditions.checkArgument(0 <= gateIndex, "The gate index must be positive.");
		this.gateIndex = gateIndex;

		this.consumedResultId = checkNotNull(consumedResultId);
		this.consumedPartitionType = checkNotNull(consumedPartitionType);
		this.bufferPoolFactory = checkNotNull(bufferPoolFactory);

		checkArgument(consumedSubpartitionIndex >= 0);
		this.consumedSubpartitionIndex = consumedSubpartitionIndex;

		checkArgument(numberOfInputChannels > 0);
		this.numberOfInputChannels = numberOfInputChannels;

		this.inputChannels = new HashMap<>(numberOfInputChannels);
		this.channels = new InputChannel[numberOfInputChannels];
		this.channelsWithEndOfPartitionEvents = new BitSet(numberOfInputChannels);
		this.enqueuedInputChannelsWithData = new BitSet(numberOfInputChannels);

		this.partitionProducerStateProvider = checkNotNull(partitionProducerStateProvider);

		this.bufferDecompressor = bufferDecompressor;
		this.memorySegmentProvider = checkNotNull(memorySegmentProvider);

		this.closeFuture = new CompletableFuture<>();
	}

	/*************************************************
	 * 
	 *  注释： Bbuffer  MemorySegment
	 *  流式计算引擎： 上游Task执行完毕一条数据的计算之后，就会发送这条数据的计算结果给下游Task
	 *  到底怎么给，规则是由  StreamPartitioiner 来指定的
	 *  一条数据在一个Task 执行完毕之后，就要发送给下游个另外一个Task
	 *  这个过程，这个网络数据传输过程，是由 Netty 支持的，具体是由 IntputChannel 实现
	 *  Buffer Channel
	 */
	@Override
	public void setup() throws IOException {
		checkState(this.bufferPool == null, "Bug in input gate setup logic: Already registered buffer pool.");

		// TODO_MA 注释： 为 InputChannel 分配 Buffer
		// assign exclusive buffers to input channels directly and use the rest for floating buffers
		assignExclusiveSegments();

		/*************************************************
		 * 
		 *  注释： 设置 BufferPool
		 *  内存管理有关！
		 *  1、在海量数据处理中，JVM 的堆内存的管理方式有很大的缺陷， 每次从 堆内存中申请的不是一个 32kb 的 MemorySegement
		 *  2、BufferPool 就是管理 MemorySegement 的
		 */
		BufferPool bufferPool = bufferPoolFactory.get();
		setBufferPool(bufferPool);
	}

	@Override
	public CompletableFuture<?> readRecoveredState(ExecutorService executor, ChannelStateReader reader) {
		List<CompletableFuture<?>> futures = getStateConsumedFuture();

		executor.submit(() -> {
			Collection<InputChannel> channels;
			synchronized(requestLock) {
				channels = inputChannels.values();
			}
			internalReadRecoveredState(reader, channels);
		});

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}

	private List<CompletableFuture<?>> getStateConsumedFuture() {
		synchronized(requestLock) {
			List<CompletableFuture<?>> futures = new ArrayList<>(inputChannels.size());
			for(InputChannel inputChannel : inputChannels.values()) {
				if(inputChannel instanceof RecoveredInputChannel) {
					futures.add(((RecoveredInputChannel) inputChannel).getStateConsumedFuture());
				}
			}
			return futures;
		}
	}

	private void internalReadRecoveredState(ChannelStateReader reader, Collection<InputChannel> inputChannels) {
		for(InputChannel inputChannel : inputChannels) {
			try {
				if(inputChannel instanceof RecoveredInputChannel) {
					((RecoveredInputChannel) inputChannel).readRecoveredState(reader);
				}
			} catch(Throwable t) {
				inputChannel.setError(t);
				return;
			}
		}
	}

	// TODO_MA 注释： 请求分区
	@Override
	public void requestPartitions() {
		synchronized(requestLock) {

			// TODO_MA 注释： 只请求一次
			if(!requestedPartitionsFlag) {
				if(closeFuture.isDone()) {
					throw new IllegalStateException("Already released.");
				}

				// Sanity checks
				if(numberOfInputChannels != inputChannels.size()) {
					throw new IllegalStateException(String.format(
						"Bug in input gate setup logic: mismatch between " + "number of total input channels [%s] and the currently set number of input " + "channels [%s].",
						inputChannels.size(), numberOfInputChannels));
				}

				/*************************************************
				 * 
				 *  注释：
				 */
				convertRecoveredInputChannels();

				/*************************************************
				 * 
				 *  注释：
				 */
				internalRequestPartitions();
			}

			requestedPartitionsFlag = true;
		}
	}

	@VisibleForTesting
	void convertRecoveredInputChannels() {
		for(Map.Entry<IntermediateResultPartitionID, InputChannel> entry : inputChannels.entrySet()) {
			InputChannel inputChannel = entry.getValue();
			if(inputChannel instanceof RecoveredInputChannel) {
				try {

					/*************************************************
					 * 
					 *  注释：
					 */
					InputChannel realInputChannel = ((RecoveredInputChannel) inputChannel).toInputChannel();

					/*************************************************
					 * 
					 *  注释：
					 */
					inputChannel.releaseAllResources();
					entry.setValue(realInputChannel);
					channels[inputChannel.getChannelIndex()] = realInputChannel;
				} catch(Throwable t) {
					inputChannel.setError(t);
					return;
				}
			}
		}
	}

	private void internalRequestPartitions() {
		for(InputChannel inputChannel : inputChannels.values()) {
			try {
				/*************************************************
				 * 
				 *  注释： 每一个channel都请求对应的子分区
				 */
				inputChannel.requestSubpartition(consumedSubpartitionIndex);
			} catch(Throwable t) {
				inputChannel.setError(t);
				return;
			}
		}
	}

	@Override
	public void registerBufferReceivedListener(BufferReceivedListener bufferReceivedListener) {
		checkState(this.bufferReceivedListener == null, "Trying to overwrite the buffer received listener");
		this.bufferReceivedListener = checkNotNull(bufferReceivedListener);
	}

	// ------------------------------------------------------------------------
	// Properties
	// ------------------------------------------------------------------------

	@Override
	public int getNumberOfInputChannels() {
		return numberOfInputChannels;
	}

	@Override
	public int getGateIndex() {
		return gateIndex;
	}

	@Nullable
	BufferReceivedListener getBufferReceivedListener() {
		return bufferReceivedListener;
	}

	/**
	 * Returns the type of this input channel's consumed result partition.
	 *
	 * @return consumed result partition type
	 */
	public ResultPartitionType getConsumedPartitionType() {
		return consumedPartitionType;
	}

	BufferProvider getBufferProvider() {
		return bufferPool;
	}

	public BufferPool getBufferPool() {
		return bufferPool;
	}

	MemorySegmentProvider getMemorySegmentProvider() {
		return memorySegmentProvider;
	}

	public String getOwningTaskName() {
		return owningTaskName;
	}

	public int getNumberOfQueuedBuffers() {
		// re-try 3 times, if fails, return 0 for "unknown"
		for(int retry = 0; retry < 3; retry++) {
			try {
				int totalBuffers = 0;

				for(InputChannel channel : inputChannels.values()) {
					totalBuffers += channel.unsynchronizedGetNumberOfQueuedBuffers();
				}

				return totalBuffers;
			} catch(Exception ignored) {
			}
		}

		return 0;
	}

	public CompletableFuture<Void> getCloseFuture() {
		return closeFuture;
	}

	@Override
	public InputChannel getChannel(int channelIndex) {
		return channels[channelIndex];
	}

	// ------------------------------------------------------------------------
	// Setup/Life-cycle
	// ------------------------------------------------------------------------

	public void setBufferPool(BufferPool bufferPool) {
		checkState(this.bufferPool == null, "Bug in input gate setup logic: buffer pool has" + "already been set for this input gate.");

		this.bufferPool = checkNotNull(bufferPool);
	}

	/**
	 * // TODO_MA 注释： 为 InputChannel 分配 Buffer
	 * // TODO_MA 注释： 直接为基于信用的模式将独占缓冲区分配给所有远程输入通道。
	 * Assign the exclusive buffers to all remote input channels directly for credit-based mode.
	 */
	@VisibleForTesting
	public void assignExclusiveSegments() throws IOException {
		synchronized(requestLock) {

			/*************************************************
			 * 
			 *  注释： 一个 InputGate 中，根据需要上游的 几个 Task 拉取数据，就会有多少个 InputChannel
			 */
			for(InputChannel inputChannel : inputChannels.values()) {
				// Note that although the initial channel would not be RemoteInputChannel at the moment,
				// we might change to generate different type channels based on config future.

				// TODO_MA 注释： 如果 inputChannel 的实现是： RemoteInputChannel
				if(inputChannel instanceof RemoteInputChannel) {
					((RemoteInputChannel) inputChannel).assignExclusiveSegments();
				}

				// TODO_MA 注释： 如果 inputChannel 的实现是： RemoteRecoveredInputChannel
				else if(inputChannel instanceof RemoteRecoveredInputChannel) {
					((RemoteRecoveredInputChannel) inputChannel).assignExclusiveSegments();
				}
			}
		}
	}

	public void setInputChannels(InputChannel... channels) {
		if(channels.length != numberOfInputChannels) {
			throw new IllegalArgumentException("Expected " + numberOfInputChannels + " channels, " + "but got " + channels.length);
		}
		synchronized(requestLock) {
			System.arraycopy(channels, 0, this.channels, 0, numberOfInputChannels);

			/*************************************************
			 * 
			 *  注释： 把 InputChannel 和 IntermediateResultPartition 对应上
			 *  IntermediateResultPartition 是 ExecutionGraph 的概念
			 *  真正执行的对应到一个 ResultPartition
			 */
			for(InputChannel inputChannel : channels) {
				IntermediateResultPartitionID partitionId = inputChannel.getPartitionId().getPartitionId();

				/*************************************************
				 * 
				 *  注释： 将 IntermediateResultPartitionID 和 InputChannel 映射起来
				 */
				if(inputChannels.put(partitionId, inputChannel) == null && inputChannel instanceof UnknownInputChannel) {

					numberOfUninitializedChannels++;
				}
			}
		}
	}

	public void updateInputChannel(ResourceID localLocation, NettyShuffleDescriptor shuffleDescriptor) throws IOException, InterruptedException {
		synchronized(requestLock) {
			if(closeFuture.isDone()) {
				// There was a race with a task failure/cancel
				return;
			}

			IntermediateResultPartitionID partitionId = shuffleDescriptor.getResultPartitionID().getPartitionId();

			InputChannel current = inputChannels.get(partitionId);

			if(current instanceof UnknownInputChannel) {
				UnknownInputChannel unknownChannel = (UnknownInputChannel) current;
				boolean isLocal = shuffleDescriptor.isLocalTo(localLocation);
				InputChannel newChannel;
				if(isLocal) {
					newChannel = unknownChannel.toLocalInputChannel();
				} else {
					RemoteInputChannel remoteInputChannel = unknownChannel.toRemoteInputChannel(shuffleDescriptor.getConnectionId());
					remoteInputChannel.assignExclusiveSegments();
					newChannel = remoteInputChannel;
				}
				LOG.debug("{}: Updated unknown input channel to {}.", owningTaskName, newChannel);

				inputChannels.put(partitionId, newChannel);
				channels[current.getChannelIndex()] = newChannel;

				if(requestedPartitionsFlag) {
					newChannel.requestSubpartition(consumedSubpartitionIndex);
				}

				for(TaskEvent event : pendingEvents) {
					newChannel.sendTaskEvent(event);
				}

				if(--numberOfUninitializedChannels == 0) {
					pendingEvents.clear();
				}
			}
		}
	}

	/**
	 * Retriggers a partition request.
	 */
	public void retriggerPartitionRequest(IntermediateResultPartitionID partitionId) throws IOException {
		synchronized(requestLock) {
			if(!closeFuture.isDone()) {
				final InputChannel ch = inputChannels.get(partitionId);

				checkNotNull(ch, "Unknown input channel with ID " + partitionId);

				LOG.debug("{}: Retriggering partition request {}:{}.", owningTaskName, ch.partitionId, consumedSubpartitionIndex);

				/*************************************************
				 * 
				 *  注释：
				 */
				if(ch.getClass() == RemoteInputChannel.class) {
					final RemoteInputChannel rch = (RemoteInputChannel) ch;

					/*************************************************
					 * 
					 *  注释： 注册
					 */
					rch.retriggerSubpartitionRequest(consumedSubpartitionIndex);
				}

				/*************************************************
				 * 
				 *  注释：
				 */
				else if(ch.getClass() == LocalInputChannel.class) {
					final LocalInputChannel ich = (LocalInputChannel) ch;
					if(retriggerLocalRequestTimer == null) {
						retriggerLocalRequestTimer = new Timer(true);
					}
					ich.retriggerSubpartitionRequest(retriggerLocalRequestTimer, consumedSubpartitionIndex);
				} else {
					throw new IllegalStateException("Unexpected type of channel to retrigger partition: " + ch.getClass());
				}
			}
		}
	}

	@VisibleForTesting
	Timer getRetriggerLocalRequestTimer() {
		return retriggerLocalRequestTimer;
	}

	@Override
	public void close() throws IOException {
		boolean released = false;
		synchronized(requestLock) {
			if(!closeFuture.isDone()) {
				try {
					LOG.debug("{}: Releasing {}.", owningTaskName, this);

					if(retriggerLocalRequestTimer != null) {
						retriggerLocalRequestTimer.cancel();
					}

					for(InputChannel inputChannel : inputChannels.values()) {
						try {
							inputChannel.releaseAllResources();
						} catch(IOException e) {
							LOG.warn("{}: Error during release of channel resources: {}.", owningTaskName, e.getMessage(), e);
						}
					}

					// The buffer pool can actually be destroyed immediately after the
					// reader received all of the data from the input channels.
					if(bufferPool != null) {
						bufferPool.lazyDestroy();
					}
				} finally {
					released = true;
					closeFuture.complete(null);
				}
			}
		}

		if(released) {
			synchronized(inputChannelsWithData) {
				inputChannelsWithData.notifyAll();
			}
		}
	}

	@Override
	public boolean isFinished() {
		return hasReceivedAllEndOfPartitionEvents;
	}

	// ------------------------------------------------------------------------
	// Consume
	// ------------------------------------------------------------------------

	@Override
	public Optional<BufferOrEvent> getNext() throws IOException, InterruptedException {

		/*************************************************
		 * 
		 *  注释： 阻塞 获取输入
		 */
		return getNextBufferOrEvent(true);
	}

	@Override
	public Optional<BufferOrEvent> pollNext() throws IOException, InterruptedException {

		/*************************************************
		 * 
		 *  注释： 非阻塞 获取输入
		 */
		return getNextBufferOrEvent(false);
	}

	/*************************************************
	 * 
	 *  注释： 首先尝试请求分区，实际的请求只会执行一次
	 */
	private Optional<BufferOrEvent> getNextBufferOrEvent(boolean blocking) throws IOException, InterruptedException {
		if(hasReceivedAllEndOfPartitionEvents) {
			return Optional.empty();
		}

		if(closeFuture.isDone()) {
			throw new CancelTaskException("Input gate is already closed.");
		}

		/*************************************************
		 * 
		 *  注释： 获取可用数据
		 */
		Optional<InputWithData<InputChannel, BufferAndAvailability>> next = waitAndGetNextData(blocking);
		if(!next.isPresent()) {
			return Optional.empty();
		}

		// TODO_MA 注释： 获取 InputWithData
		InputWithData<InputChannel, BufferAndAvailability> inputWithData = next.get();

		/*************************************************
		 * 
		 *  注释： 针对 InputWithData 执行处理
		 */
		return Optional.of(transformToBufferOrEvent(inputWithData.data.buffer(), inputWithData.moreAvailable, inputWithData.input));
	}

	/*************************************************
	 * 
	 *  注释： 获取可用数据
	 */
	private Optional<InputWithData<InputChannel, BufferAndAvailability>> waitAndGetNextData(
		boolean blocking) throws IOException, InterruptedException {

		// TODO_MA 注释： 循环
		while(true) {

			/*************************************************
			 * 
			 *  注释： 获取有数据的 channel
			 */
			Optional<InputChannel> inputChannel = getChannel(blocking);

			if(!inputChannel.isPresent()) {
				return Optional.empty();
			}

			// TODO_MA 注释： 获取 存储数据的 buffer
			// Do not query inputChannel under the lock, to avoid potential deadlocks coming from notifications.
			Optional<BufferAndAvailability> result = inputChannel.get().getNextBuffer();

			synchronized(inputChannelsWithData) {
				if(result.isPresent() && result.get().moreAvailable()) {
					// enqueue the inputChannel at the end to avoid starvation
					inputChannelsWithData.add(inputChannel.get());
					enqueuedInputChannelsWithData.set(inputChannel.get().getChannelIndex());
				}

				if(inputChannelsWithData.isEmpty()) {
					availabilityHelper.resetUnavailable();
				}

				/*************************************************
				 * 
				 *  注释： 最终返回 InputWithData 对象
				 */
				if(result.isPresent()) {
					return Optional.of(new InputWithData<>(inputChannel.get(), result.get(), !inputChannelsWithData.isEmpty()));
				}
			}
		}
	}

	private BufferOrEvent transformToBufferOrEvent(Buffer buffer, boolean moreAvailable,
		InputChannel currentChannel) throws IOException, InterruptedException {
		if(buffer.isBuffer()) {

			/*************************************************
			 * 
			 *  注释： 执行转换处理
			 */
			return transformBuffer(buffer, moreAvailable, currentChannel);
		} else {

			/*************************************************
			 * 
			 *  注释： 执行事件处理
			 */
			return transformEvent(buffer, moreAvailable, currentChannel);
		}
	}

	private BufferOrEvent transformBuffer(Buffer buffer, boolean moreAvailable, InputChannel currentChannel) {

		/*************************************************
		 * 
		 *  注释： 根据buffer中的数据，创建一个 BufferOrEvent 对象
		 */
		return new BufferOrEvent(decompressBufferIfNeeded(buffer), currentChannel.getChannelInfo(), moreAvailable);
	}

	private BufferOrEvent transformEvent(Buffer buffer, boolean moreAvailable,
		InputChannel currentChannel) throws IOException, InterruptedException {
		final AbstractEvent event;
		try {
			/*************************************************
			 * 
			 *  注释： 从 buffer 中，获取转换得到 event
			 */
			event = EventSerializer.fromBuffer(buffer, getClass().getClassLoader());
		} finally {
			buffer.recycleBuffer();
		}

		if(event.getClass() == EndOfPartitionEvent.class) {
			channelsWithEndOfPartitionEvents.set(currentChannel.getChannelIndex());

			if(channelsWithEndOfPartitionEvents.cardinality() == numberOfInputChannels) {
				// Because of race condition between:
				// 1. releasing inputChannelsWithData lock in this method and reaching this place
				// 2. empty data notification that re-enqueues a channel
				// we can end up with moreAvailable flag set to true, while we expect no more data.
				checkState(!moreAvailable || !pollNext().isPresent());
				moreAvailable = false;
				hasReceivedAllEndOfPartitionEvents = true;
				markAvailable();
			}

			currentChannel.releaseAllResources();
		}

		/*************************************************
		 * 
		 *  注释： 构建一个 BufferOrEvent 对象
		 */
		return new BufferOrEvent(event, currentChannel.getChannelInfo(), moreAvailable, buffer.getSize());
	}

	private Buffer decompressBufferIfNeeded(Buffer buffer) {
		if(buffer.isCompressed()) {
			try {
				checkNotNull(bufferDecompressor, "Buffer decompressor not set.");

				/*************************************************
				 * 
				 *  注释： 解压缩到中间缓冲区
				 */
				return bufferDecompressor.decompressToIntermediateBuffer(buffer);
			} finally {

				// TODO_MA 注释： 回收缓冲区
				buffer.recycleBuffer();
			}
		}
		return buffer;
	}

	private void markAvailable() {
		CompletableFuture<?> toNotify;
		synchronized(inputChannelsWithData) {
			toNotify = availabilityHelper.getUnavailableToResetAvailable();
		}
		toNotify.complete(null);
	}

	@Override
	public void sendTaskEvent(TaskEvent event) throws IOException {
		synchronized(requestLock) {
			for(InputChannel inputChannel : inputChannels.values()) {
				inputChannel.sendTaskEvent(event);
			}

			if(numberOfUninitializedChannels > 0) {
				pendingEvents.add(event);
			}
		}
	}

	@Override
	public void resumeConsumption(int channelIndex) throws IOException {
		// BEWARE: consumption resumption only happens for streaming jobs in which all slots
		// are allocated together so there should be no UnknownInputChannel. As a result, it
		// is safe to not synchronize the requestLock here. We will refactor the code to not
		// rely on this assumption in the future.
		channels[channelIndex].resumeConsumption();
	}

	// ------------------------------------------------------------------------
	// Channel notifications
	// ------------------------------------------------------------------------

	/*************************************************
	 * 
	 *  注释： 当一个 InputChannel 有数据时的回调
	 */
	void notifyChannelNonEmpty(InputChannel channel) {

		/*************************************************
		 * 
		 *  注释： 某个 channel 有可写入数了，该干活了。
		 */
		queueChannel(checkNotNull(channel));
	}

	void triggerPartitionStateCheck(ResultPartitionID partitionId) {
		partitionProducerStateProvider
			.requestPartitionProducerState(consumedResultId, partitionId, ((PartitionProducerStateProvider.ResponseHandle responseHandle) -> {
				boolean isProducingState = new RemoteChannelStateChecker(partitionId, owningTaskName)
					.isProducerReadyOrAbortConsumption(responseHandle);
				if(isProducingState) {
					try {
						retriggerPartitionRequest(partitionId.getPartitionId());
					} catch(IOException t) {
						responseHandle.failConsumption(t);
					}
				}
			}));
	}

	/*************************************************
	 * 
	 *  注释： 将新的channel加入队列
	 */
	private void queueChannel(InputChannel channel) {
		int availableChannels;

		CompletableFuture<?> toNotify = null;

		synchronized(inputChannelsWithData) {

			// TODO_MA 注释： 判断这个channel是否已经在队列中
			if(enqueuedInputChannelsWithData.get(channel.getChannelIndex())) {
				return;
			}
			availableChannels = inputChannelsWithData.size();

			/*************************************************
			 * 
			 *  注释： 加入队列中
			 *  既然将 有数据可用的channel 加入到 inputChannelsWithData，
			 *  那就证明，一定有其他的什么角色来从这个队列中获取 可用的channel 来消费数据
			 */
			inputChannelsWithData.add(channel);

			enqueuedInputChannelsWithData.set(channel.getChannelIndex());

			/*************************************************
			 * 
			 *  注释： 发送信号！
			 */
			if(availableChannels == 0) {
				// TODO_MA 注释： 如果之前队列中没有channel，这个channel加入后，通知等待的线程
				inputChannelsWithData.notifyAll();
				toNotify = availabilityHelper.getUnavailableToResetAvailable();
			}
		}

		if(toNotify != null) {
			toNotify.complete(null);
		}
	}

	private Optional<InputChannel> getChannel(boolean blocking) throws InterruptedException {
		synchronized(inputChannelsWithData) {

			/*************************************************
			 * 
			 *  注释： 如果现在还没有数据，就阻塞
			 */
			while(inputChannelsWithData.size() == 0) {
				if(closeFuture.isDone()) {
					throw new IllegalStateException("Released");
				}

				/*************************************************
				 * 
				 *  注释： 阻塞
				 */
				if(blocking) {
					inputChannelsWithData.wait();
				} else {
					availabilityHelper.resetUnavailable();
					return Optional.empty();
				}
			}

			InputChannel inputChannel = inputChannelsWithData.remove();
			enqueuedInputChannelsWithData.clear(inputChannel.getChannelIndex());
			return Optional.of(inputChannel);
		}
	}

	// ------------------------------------------------------------------------

	public Map<IntermediateResultPartitionID, InputChannel> getInputChannels() {
		return inputChannels;
	}
}
