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

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.util.FatalExitExceptionHandler;
import org.apache.flink.streaming.api.checkpoint.ExternallyInducedSource;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * {@link StreamTask} for executing a {@link StreamSource}.
 *
 * // TODO_MA 注释： 其中一个重要方面是，元素的检查点和发射一定不能同时发生。执行必须是串行的。
 * <p>One important aspect of this is that the checkpointing and the emission of elements must never
 * occur at the same time. The execution must be serial.
 *
 * // TODO_MA 注释： 这是通过与{@link SourceFunction}签订合同而实现的，即它只能修改其状态或发出锁定在锁对象上的同步块中的元素。
 * This is achieved by having the contract with the {@link SourceFunction} that it must only modify its state
 * or emit elements in a synchronized block that locks on the lock Object.
 *
 * // TODO_MA 注释： 同样，状态的修改和元素的发射必须在受同步块保护的同一代码块中进行。
 * Also, the modification of the state and the emission of elements must happen in the same block of code
 * that is protected by the synchronized block.
 *
 * @param <OUT> Type of the output elements of this source.
 * @param <SRC> Type of the source function for the stream source operator
 * @param <OP>  Type of the stream source operator
 */
@Internal
public class SourceStreamTask<OUT, SRC extends SourceFunction<OUT>, OP extends StreamSource<OUT, SRC>> extends StreamTask<OUT, OP> {

	/*************************************************
	 * 
	 *  注释： 接收数据的 线程
	 */
	private final LegacySourceFunctionThread sourceThread;
	private final Object lock;

	private volatile boolean externallyInducedCheckpoints;

	/**
	 * Indicates whether this Task was purposefully finished (by finishTask()), in this case we
	 * want to ignore exceptions thrown after finishing, to ensure shutdown works smoothly.
	 */
	private volatile boolean isFinished = false;

	/*************************************************
	 * 
	 *  注释： 当构造 Task 具体启动实例的时候，会调用这个
	 *  Environment env = RunTimeEnvironment
	 */
	public SourceStreamTask(Environment env) throws Exception {
		this(env, new Object());
	}

	/*************************************************
	 * 
	 *  注释： SourceStreamTask 其实是 Flink job 的最开始的 Task， 毫无疑问，就是对接 Source 的Task
	 *  有一个专门的线程来接收数据： LegacySourceFunctionThread
	 */
	private SourceStreamTask(Environment env, Object lock) throws Exception {

		/*************************************************
		 * 
		 *  注释： SynchronizedStreamTaskActionExecutor
		 */
		super(env, null, FatalExitExceptionHandler.INSTANCE, StreamTaskActionExecutor.synchronizedExecutor(lock));

		this.lock = Preconditions.checkNotNull(lock);

		// TODO_MA 注释： 初始化一个线程：LegacySourceFunctionThread
		// TODO_MA 注释： 这是 source 用于产生 data 的一个线程
		this.sourceThread = new LegacySourceFunctionThread();
	}

	@Override
	protected void init() {

		// TODO_MA 注释： 我们检查源是否实际上在诱发检查点，而不是触发
		// we check if the source is actually inducing the checkpoints, rather than the trigger
		SourceFunction<?> source = headOperator.getUserFunction();

		// TODO_MA 注释： 如果是外部诱导源，就注册一个 savepoint 钩子
		if(source instanceof ExternallyInducedSource) {
			externallyInducedCheckpoints = true;

			/*************************************************
			 * 
			 *  注释： 外部诱导源, 注册钩子
			 *  其实就是手动 savepoint 的钩子，不要自行定时调度。随时可执行
			 */
			ExternallyInducedSource.CheckpointTrigger triggerHook = new ExternallyInducedSource.CheckpointTrigger() {

				@Override
				public void triggerCheckpoint(long checkpointId) throws FlinkException {
					// TODO - we need to see how to derive those. We should probably not encode this in the
					// TODO -   source's trigger message, but do a handshake in this task between the trigger
					// TODO -   message from the master, and the source's trigger notification
					final CheckpointOptions checkpointOptions = CheckpointOptions
						.forCheckpointWithDefaultLocation(configuration.isExactlyOnceCheckpointMode(), configuration.isUnalignedCheckpointsEnabled());
					final long timestamp = System.currentTimeMillis();

					final CheckpointMetaData checkpointMetaData = new CheckpointMetaData(checkpointId, timestamp);

					try {

						/*************************************************
						 * 
						 *  注释： 手动触发的 savepoint 逻辑和 checkpoint 公用相同的逻辑。
						 *  唯一不同的点在于： savepoint 是手动触发， checkpoint 是定时调度触发
						 */
						SourceStreamTask.super.triggerCheckpointAsync(checkpointMetaData, checkpointOptions, false).get();
					} catch(RuntimeException e) {
						throw e;
					} catch(Exception e) {
						throw new FlinkException(e.getMessage(), e);
					}
				}
			};

			((ExternallyInducedSource<?, ?>) source).setCheckpointTrigger(triggerHook);
		}
		getEnvironment().getMetricGroup().getIOMetricGroup().gauge(MetricNames.CHECKPOINT_START_DELAY_TIME, this::getAsyncCheckpointStartDelayNanos);
	}

	@Override
	protected void advanceToEndOfEventTime() throws Exception {

		/*************************************************
		 * 
		 *  注释： 调用 OperatorChain 中的 headOperator 的 advanceToEndOfEventTime() 方法
		 */
		headOperator.advanceToEndOfEventTime();
	}

	@Override
	protected void cleanup() {
		// does not hold any resources, so no cleanup needed
	}

	@Override
	protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {

		/*************************************************
		 * 
		 *  注释： 打住！！！！！
		 *  为什么要打住，因为 sourceThread 线程中的 run 方法中的代码在调用：
		 *  headOperator.run(lock, getStreamStatusMaintainer(), operatorChain);
		 *  但是 headOperator 在此刻，并未进行初始化，当前线程的代码，还是执行在 StreamTask 的构造方法中
		 *  只有当前线程执行到 StreamTask.beforeInvoke() 的时候，内部的 headOperator 执行结束，这个地方才能放开。
		 *  这个方法的作用类似于 阻塞
		 *  -
		 *  换一种简单的说法：此时，甚至 sourceThread 都还未初始化
		 */
		controller.suspendDefaultAction();

		/*************************************************
		 * 
		 *  注释： 启动 SourceStreamTask 的 SourceThread 线程
		 */
		// Against the usual contract of this method, this implementation is not step-wise but blocking instead for
		// compatibility reasons with the current source interface (source functions run as a loop, not in steps).
		sourceThread.setTaskDescription(getName());
		sourceThread.start();

		/*************************************************
		 * 
		 *  注释： 处理启动结果，如果启动有错，则进行错误处理。
		 */
		sourceThread.getCompletionFuture().whenComplete((Void ignore, Throwable sourceThreadThrowable) -> {
			if(isCanceled() && ExceptionUtils.findThrowable(sourceThreadThrowable, InterruptedException.class).isPresent()) {
				mailboxProcessor.reportThrowable(new CancelTaskException(sourceThreadThrowable));
			} else if(!isFinished && sourceThreadThrowable != null) {
				mailboxProcessor.reportThrowable(sourceThreadThrowable);
			} else {
				mailboxProcessor.allActionsCompleted();
			}
		});
	}

	@Override
	protected void cancelTask() {
		try {
			if(headOperator != null) {
				headOperator.cancel();
			}
		} finally {
			if(sourceThread.isAlive()) {
				sourceThread.interrupt();
			} else if(!sourceThread.getCompletionFuture().isDone()) {
				// source thread didn't start
				sourceThread.getCompletionFuture().complete(null);
			}
		}
	}

	@Override
	protected void finishTask() throws Exception {
		isFinished = true;
		cancelTask();
	}

	@Override
	protected CompletableFuture<Void> getCompletionFuture() {
		return sourceThread.getCompletionFuture();
	}

	// ------------------------------------------------------------------------
	//  Checkpointing
	// ------------------------------------------------------------------------

	@Override
	public Future<Boolean> triggerCheckpointAsync(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions, boolean advanceToEndOfEventTime) {
		if(!externallyInducedCheckpoints) {

			/*************************************************
			 * 
			 *  注释： 调用父类方法
			 */
			return super.triggerCheckpointAsync(checkpointMetaData, checkpointOptions, advanceToEndOfEventTime);
		} else {
			// we do not trigger checkpoints here, we simply state whether we can trigger them
			synchronized(lock) {
				return CompletableFuture.completedFuture(isRunning());
			}
		}
	}

	@Override
	protected void declineCheckpoint(long checkpointId) {
		if(!externallyInducedCheckpoints) {
			super.declineCheckpoint(checkpointId);
		}
	}

	/**
	 * Runnable that executes the the source function in the head operator.
	 */
	private class LegacySourceFunctionThread extends Thread {

		private final CompletableFuture<Void> completionFuture;

		LegacySourceFunctionThread() {
			this.completionFuture = new CompletableFuture<>();
		}

		@Override
		public void run() {
			try {
				/*************************************************
				 * 
				 *  注释： 调用 source Operator 的 run
				 */
				headOperator.run(lock, getStreamStatusMaintainer(), operatorChain);
				completionFuture.complete(null);

			} catch(Throwable t) {
				// Note, t can be also an InterruptedException
				completionFuture.completeExceptionally(t);
			}
		}

		public void setTaskDescription(final String taskDescription) {
			setName("Legacy Source Thread - " + taskDescription);
		}

		/**
		 * @return future that is completed once this thread completes. If this task {@link #isFailing()} and this thread
		 * is not alive (e.g. not started) returns a normally completed future.
		 */
		CompletableFuture<Void> getCompletionFuture() {
			return isFailing() && !isAlive() ? CompletableFuture.completedFuture(null) : completionFuture;
		}
	}
}
