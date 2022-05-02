/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.checkpoint;

import org.apache.flink.annotation.Public;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.state.OperatorStateStore;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;

/**
 * // TODO_MA 注释： 这是 stateful transformation functions 的核心接口，意为功能维护各个流记录之间的状态。
 * // TODO_MA 注释： 尽管存在更多轻量级的接口作为各种类型状态的快捷方式，
 * // TODO_MA 注释： 但此接口在管理 keyed state 和 operator state 方面提供了最大的灵活性。
 * This is the core interface for <i>stateful transformation functions</i>, meaning functions
 * that maintain state across individual stream records.
 * While more lightweight interfaces exist as shortcuts for various types of state, this interface offer the
 * greatest flexibility in managing both <i>keyed state</i> and <i>operator state</i>.
 *
 * // TODO_MA 注释： shortcuts 部分说明了设置状态函数的常见轻量级方法，这些方法通常用于代替该接口表示的完整抽象。
 * <p>The section <a href="#shortcuts">Shortcuts</a> illustrates the common lightweight
 * ways to setup stateful functions typically used instead of the full fledged
 * abstraction represented by this interface.
 *
 * <h1>Initialization</h1>
 * The {@link CheckpointedFunction#initializeState(FunctionInitializationContext)} is called when
 * the parallel instance of the transformation function is created during distributed execution.
 * The method gives access to the {@link FunctionInitializationContext} which in turn gives access
 * to the to the {@link OperatorStateStore} and {@link KeyedStateStore}.
 *
 * <p>The {@code OperatorStateStore} and {@code KeyedStateStore} give access to the data structures
 * in which state should be stored for Flink to transparently manage and checkpoint it, such as
 * {@link org.apache.flink.api.common.state.ValueState} or
 * {@link org.apache.flink.api.common.state.ListState}.
 *
 * <p><b>Note:</b> The {@code KeyedStateStore} can only be used when the transformation supports
 * <i>keyed state</i>, i.e., when it is applied on a keyed stream (after a {@code keyBy(...)}).
 *
 * <h1>Snapshot</h1>
 * The {@link CheckpointedFunction#snapshotState(FunctionSnapshotContext)} is called whenever a
 * checkpoint takes a state snapshot of the transformation function. Inside this method, functions typically
 * make sure that the checkpointed data structures (obtained in the initialization phase) are up
 * to date for a snapshot to be taken. The given snapshot context gives access to the metadata
 * of the checkpoint.
 *
 * <p>In addition, functions can use this method as a hook to flush/commit/synchronize with
 * external systems.
 *
 * <h1>Example</h1>
 * The code example below illustrates how to use this interface for a function that keeps counts
 * of events per key and per parallel partition (parallel instance of the transformation function
 * during distributed execution).
 * The example also changes of parallelism, which affect the count-per-parallel-partition by
 * adding up the counters of partitions that get merged on scale-down. Note that this is a
 * toy example, but should illustrate the basic skeleton for a stateful function.
 *
 * <p><pre>{@code
 * public class MyFunction<T> implements MapFunction<T, T>, CheckpointedFunction {
 *
 *     private ReducingState<Long> countPerKey;
 *     private ListState<Long> countPerPartition;
 *
 *     private long localCount;
 *
 *     public void initializeState(FunctionInitializationContext context) throws Exception {
 *         // get the state data structure for the per-key state
 *         countPerKey = context.getKeyedStateStore().getReducingState(
 *                 new ReducingStateDescriptor<>("perKeyCount", new AddFunction<>(), Long.class));
 *
 *         // get the state data structure for the per-partition state
 *         countPerPartition = context.getOperatorStateStore().getOperatorState(
 *                 new ListStateDescriptor<>("perPartitionCount", Long.class));
 *
 *         // initialize the "local count variable" based on the operator state
 *         for (Long l : countPerPartition.get()) {
 *             localCount += l;
 *         }
 *     }
 *
 *     public void snapshotState(FunctionSnapshotContext context) throws Exception {
 *         // the keyed state is always up to date anyways
 *         // just bring the per-partition state in shape
 *         countPerPartition.clear();
 *         countPerPartition.add(localCount);
 *     }
 *
 *     public T map(T value) throws Exception {
 *         // update the states
 *         countPerKey.add(1L);
 *         localCount++;
 *
 *         return value;
 *     }
 * }
 * }</pre>
 *
 * <hr>
 *
 * <h1><a name="shortcuts">Shortcuts</a></h1>
 * There are various ways that transformation functions can use state without implementing the
 * full-fledged {@code CheckpointedFunction} interface:
 *
 * <h4>Operator State</h4>
 * Checkpointing some state that is part of the function object itself is possible in a simpler way
 * by directly implementing the {@link ListCheckpointed} interface.
 *
 * <h4>Keyed State</h4>
 * Access to keyed state is possible via the {@link RuntimeContext}'s methods:
 * <pre>{@code
 * public class CountPerKeyFunction<T> extends RichMapFunction<T, T> {
 *
 *     private ValueState<Long> count;
 *
 *     public void open(Configuration cfg) throws Exception {
 *         count = getRuntimeContext().getState(new ValueStateDescriptor<>("myCount", Long.class));
 *     }
 *
 *     public T map(T value) throws Exception {
 *         Long current = count.get();
 *         count.update(current == null ? 1L : current + 1);
 *
 *         return value;
 *     }
 * }
 * }</pre>
 *
 * @see ListCheckpointed
 * @see RuntimeContext
 */
@Public
public interface CheckpointedFunction {

	/**
	 * // TODO_MA 注释： 在创建检查点的时候调用
	 * This method is called when a snapshot for a checkpoint is requested. This acts as a hook to the function to
	 * ensure that all state is exposed by means previously offered through {@link FunctionInitializationContext} when
	 * the Function was initialized, or offered now by {@link FunctionSnapshotContext} itself.
	 *
	 * // TODO_MA 注释： 获得function或者operator的当前状态（快照），这个状态必须反映该function之前的变更所产生的最终结果
	 * // TODO_MA 注释： 该方法接收两个参数，第一个参数是checkpointId，表示该检查点的ID，第二个参数checkpointTimestamp，
	 * // TODO_MA 注释： 检查点的时间戳，被 JobManager 的 System.currentTimeMillis() 驱动。
	 * // TODO_MA 注释： 需要的这两个参数，都由 FunctionSnapshotContext 提供。
	 *
	 * @param context the context for drawing a snapshot of the operator
	 * @throws Exception Thrown, if state could not be created ot restored.
	 */
	void snapshotState(FunctionSnapshotContext context) throws Exception;

	/**
	 * // TODO_MA 注释： 在初始化的时候调用 (在从检查点恢复状态的时候也会先调用该方法)
	 * // TODO_MA 注释： 通过 FunctionInitializationContext 可以访问到 OperatorStateStore 和 KeyedStateStore，
	 * // TODO_MA 注释： 通过 OperatorStateStore 获取 Operator State
	 * // TODO_MA 注释： 通过 KeyedStateStore 获取 Keyed State
	 * This method is called when the parallel function instance is created during distributed execution.
	 * Functions typically set up their state storing data structures in this method.
	 *
	 * // TODO_MA 注释： 用于从之前的检查点中恢复function或operator的状态，需要注意的是该方法的调用会早于open方法
	 *
	 * @param context the context for initializing the operator
	 * @throws Exception Thrown, if state could not be created ot restored.
	 */
	void initializeState(FunctionInitializationContext context) throws Exception;
}
