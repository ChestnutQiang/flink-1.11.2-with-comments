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

/**
 * // TODO_MA 注释： {@link State}界面，用于减少状态。
 * // TODO_MA 注释： 可以将元素添加到状态中，然后将它们使用reduce函数进行组合。可以检查当前状态。
 * {@link State} interface for reducing state. Elements can be added to the state, they will
 * be combined using a reduce function. The current state can be inspected.
 *
 * // TODO_MA 注释： 该状态由用户功能访问和修改，并作为分布式快照的一部分由系统一致地*进行检查。
 * <p>The state is accessed and modified by user functions, and checkpointed consistently
 * by the system as part of the distributed snapshots.
 *
 * // TODO_MA 注释： 该状态只能由{@code KeyedStream}上应用的函数访问。
 * // TODO_MA 注释： 键是系统自动提供的，因此函数始终会看到映射到当前元素的*键的值。
 * // TODO_MA 注释： 这样，系统可以一致地一起处理流和状态分区。
 * <p>The state is only accessible by functions applied on a {@code KeyedStream}. The key is
 * automatically supplied by the system, so the function always sees the value mapped to the
 * key of the current element. That way, the system can handle stream and state partitioning
 * consistently together.
 *
 * @param <T> Type of the value in the operator state
 */
@PublicEvolving
public interface ReducingState<T> extends MergingState<T, T> {}
