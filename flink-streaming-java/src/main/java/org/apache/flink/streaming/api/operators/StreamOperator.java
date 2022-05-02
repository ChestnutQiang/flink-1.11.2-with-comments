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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.CheckpointListener;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.util.Disposable;

import java.io.Serializable;

/**
 * // TODO_MA 注释： 流运算符的基本接口。实现者将实现 {@link org.apache.flink.streaming.api.operators.OneInputStreamOperator}
 * // TODO_MA 注释： {@link org.apache.flink.streaming.api.operators.TwoInputStreamOperator} 之一，以创建用于处理元素的运算符。
 * Basic interface for stream operators. Implementers would implement one of
 * {@link org.apache.flink.streaming.api.operators.OneInputStreamOperator} or
 * {@link org.apache.flink.streaming.api.operators.TwoInputStreamOperator} to create operators
 * that process elements.
 *
 * // TODO_MA 注释： 类{@link org.apache.flink.streaming.api.operators.AbstractStreamOperator} *提供了生命周期和属性方法的默认实现。
 * <p>The class {@link org.apache.flink.streaming.api.operators.AbstractStreamOperator}
 * offers default implementation for the lifecycle and properties methods.
 *
 * // TODO_MA 注释： {@code StreamOperator}的方法保证不会被同时调用。
 * // TODO_MA 注释： 另外，如果使用计时器服务，则还保证不会与{@code StreamOperator}上的方法同时调用计时器回调。
 * <p>Methods of {@code StreamOperator} are guaranteed not to be called concurrently. Also, if using
 * the timer service, timer callbacks are also guaranteed not to be called concurrently with
 * methods on {@code StreamOperator}.
 *
 * // TODO_MA 注释： 输出类型
 *
 * @param <OUT> The output type of the operator
 */
@PublicEvolving
public interface StreamOperator<OUT> extends CheckpointListener, KeyContext, Disposable, Serializable {

	// ------------------------------------------------------------------------
	//  life cycle
	// ------------------------------------------------------------------------

	/**
	 * // TODO_MA 注释： 在处理任何元素之前立即调用此方法，该方法应包含运算符的初始化逻辑。
	 * This method is called immediately before any elements are processed, it should contain the
	 * operator's initialization logic.
	 *
	 * // TODO_MA 注释： 在恢复的情况下，此方法需要确保在通过返回控制之前处理所有恢复的数据，
	 * // TODO_MA 注释： 以便在操作符链恢复期间确保元素的顺序（操作符*从尾部操作符打开到头部操作符）
	 *
	 * @throws java.lang.Exception An exception in this method causes the operator to fail.
	 * @implSpec In case of recovery, this method needs to ensure that all recovered data is processed before passing
	 * back control, so that the order of elements is ensured during the recovery of an operator chain (operators
	 * are opened from the tail operator to the head operator).
	 */
	void open() throws Exception;

	/**
	 * This method is called after all records have been added to the operators via the methods
	 * {@link org.apache.flink.streaming.api.operators.OneInputStreamOperator#processElement(StreamRecord)}, or
	 * {@link org.apache.flink.streaming.api.operators.TwoInputStreamOperator#processElement1(StreamRecord)} and
	 * {@link org.apache.flink.streaming.api.operators.TwoInputStreamOperator#processElement2(StreamRecord)}.
	 *
	 * <p>The method is expected to flush all remaining buffered data. Exceptions during this
	 * flushing of buffered should be propagated, in order to cause the operation to be recognized
	 * as failed, because the last data items are not processed properly.
	 *
	 * @throws java.lang.Exception An exception in this method causes the operator to fail.
	 */
	void close() throws Exception;

	/**
	 * // TODO_MA 注释： 在成功完成操作的情况下，以及在失败和取消的情况下，都在操作员生命的尽头调用此方法。
	 * This method is called at the very end of the operator's life, both in the case of a successful
	 * completion of the operation, and in the case of a failure and canceling.
	 *
	 * // TODO_MA 注释： 预计此方法将为释放操作员已获取的所有资源做出充分的努力。
	 * <p>This method is expected to make a thorough effort to release all resources
	 * that the operator has acquired.
	 */
	@Override
	void dispose() throws Exception;

	// ------------------------------------------------------------------------
	//  state snapshots
	// ------------------------------------------------------------------------

	/**
	 * // TODO_MA 注释： 当操作员在发出自己的检查点屏障之前应进行快照时，将调用此方法。
	 * This method is called when the operator should do a snapshot, before it emits its own checkpoint barrier.
	 *
	 * <p>This method is intended not for any actual state persistence, but only for emitting some
	 * data before emitting the checkpoint barrier. Operators that maintain some small transient state
	 * that is inefficient to checkpoint (especially when it would need to be checkpointed in a
	 * re-scalable way) but can simply be sent downstream before the checkpoint. An example are
	 * opportunistic pre-aggregation operators, which have small the pre-aggregation state that is
	 * frequently flushed downstream.
	 *
	 * // TODO_MA 注释： 此方法不应用于任何实际状态快照逻辑，因为它本质上将位于操作员检查点的同步部分内。
	 * // TODO_MA 注释： 如果在此方法中完成繁重的工作，将影响等待时间和下游检查点对齐。
	 * <p><b>Important:</b> This method should not be used for any actual state snapshot logic, because
	 * it will inherently be within the synchronous part of the operator's checkpoint. If heavy work is done
	 * within this method, it will affect latency and downstream checkpoint alignments.
	 *
	 * @param checkpointId The ID of the checkpoint.
	 * @throws Exception Throwing an exception here causes the operator to fail and go into recovery.
	 */
	void prepareSnapshotPreBarrier(long checkpointId) throws Exception;

	/**
	 * // TODO_MA 注释： 从操作员处调用以绘制状态快照。
	 * Called to draw a state snapshot from the operator.
	 *
	 * // TODO_MA 注释： 状态句柄的可运行的Future，它指向快照状态。对于同步实现， 可运行对象可能已经完成。
	 *
	 * @return a runnable future to the state handle that points to the snapshotted state. For synchronous implementations,
	 * the runnable might already be finished.
	 * @throws Exception exception that happened during snapshotting.
	 */
	OperatorSnapshotFutures snapshotState(long checkpointId, long timestamp, CheckpointOptions checkpointOptions,
		CheckpointStreamFactory storageLocation) throws Exception;

	/**
	 * // TODO_MA 注释： 提供上下文以初始化运算符中的所有状态。
	 * Provides a context to initialize all state in the operator.
	 */
	void initializeState(StreamTaskStateInitializer streamTaskStateManager) throws Exception;

	// ------------------------------------------------------------------------
	//  miscellaneous
	// ------------------------------------------------------------------------

	void setKeyContextElement1(StreamRecord<?> record) throws Exception;

	void setKeyContextElement2(StreamRecord<?> record) throws Exception;

	MetricGroup getMetricGroup();

	OperatorID getOperatorID();
}
