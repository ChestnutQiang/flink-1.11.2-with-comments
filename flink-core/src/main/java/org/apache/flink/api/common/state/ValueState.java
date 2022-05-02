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

package org.apache.flink.api.common.state;

import org.apache.flink.annotation.PublicEvolving;

import java.io.IOException;

/**
 * // TODO_MA 注释： {@link State}接口，用于分区单值状态。该值可以检索或更新。
 * {@link State} interface for partitioned single-value state. The value can be retrieved or
 * updated.
 *
 * // TODO_MA 注释： 该状态由用户功能访问和修改，并作为分布式快照的一部分由系统一致地进行检查。
 * <p>The state is accessed and modified by user functions, and checkpointed consistently
 * by the system as part of the distributed snapshots.
 *
 * // TODO_MA 注释： 该状态只能由 {@code KeyedStream} 上应用的函数访问。
 * // TODO_MA 注释： 键是系统自动提供的，因此函数始终会看到映射到当前元素的键的值。
 * // TODO_MA 注释： 这样，系统可以一致地一起处理流和状态分区。
 * <p>The state is only accessible by functions applied on a {@code KeyedStream}. The key is
 * automatically supplied by the system, so the function always sees the value mapped to the
 * key of the current element. That way, the system can handle stream and state partitioning
 * consistently together.
 *
 * @param <T> Type of the value in the state.
 */
@PublicEvolving
public interface ValueState<T> extends State {

	/**
	 * Returns the current value for the state. When the state is not
	 * partitioned the returned value is the same for all inputs in a given
	 * operator instance. If state partitioning is applied, the value returned
	 * depends on the current operator input, as the operator maintains an
	 * independent state for each partition.
	 *
	 * <p>If you didn't specify a default value when creating the {@link ValueStateDescriptor}
	 * this will return {@code null} when to value was previously set using {@link #update(Object)}.
	 *
	 * @return The state value corresponding to the current input.
	 * @throws IOException Thrown if the system cannot access the state.
	 */
	T value() throws IOException;

	/**
	 * Updates the operator state accessible by {@link #value()} to the given
	 * value. The next time {@link #value()} is called (for the same state
	 * partition) the returned state will represent the updated value. When a
	 * partitioned state is updated with null, the state for the current key
	 * will be removed and the default value is returned on the next access.
	 *
	 * @param value The new value for the state.
	 * @throws IOException Thrown if the system cannot access the state.
	 */
	void update(T value) throws IOException;

}
