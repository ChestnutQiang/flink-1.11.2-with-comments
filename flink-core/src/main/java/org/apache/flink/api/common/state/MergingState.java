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
 * // TODO_MA 注释： {@link AppendingState} 的扩展，允许合并状态。也就是说，可以将{@link MergingState}
 * // TODO_MA 注释： 的两个实例合并为一个包含两个合并状态的所有信息的实例。
 * Extension of {@link AppendingState} that allows merging of state. That is, two instances
 * of {@link MergingState} can be combined into a single instance that contains all the
 * information of the two merged states.
 *
 * @param <IN>  Type of the value that can be added to the state.
 * @param <OUT> Type of the value that can be retrieved from the state.
 */
@PublicEvolving
public interface MergingState<IN, OUT> extends AppendingState<IN, OUT> {
}
