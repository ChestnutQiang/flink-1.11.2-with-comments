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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.PublicEvolving;

/**
 * Defines the chaining scheme for the operator. When an operator is chained to the
 * predecessor, it means that they run in the same thread. They become one operator
 * consisting of multiple steps.
 *
 * <p>The default value used by the StreamOperator is {@link #HEAD}, which means that
 * the operator is not chained to its predecessor. Most operators override this with
 * {@link #ALWAYS}, meaning they will be chained to predecessors whenever possible.
 */
@PublicEvolving
public enum ChainingStrategy {

	/**
	 * // TODO_MA 注释： Operators将竭尽所能地链接在一起。
	 * Operators will be eagerly chained whenever possible.
	 *
	 * // TODO_MA 注释： 为了优化性能，通常最好是允许 maximal chaining 并增加 operator 的并行度。
	 * <p>To optimize performance, it is generally a good practice to allow maximal chaining and increase operator parallelism.
	 */
	ALWAYS,

	/**
	 * // TODO_MA 注释： 该运算符将不会被链接到之前或之后的运算符
	 * The operator will not be chained to the preceding or succeeding operators.
	 */
	NEVER,

	/**
	 * // TODO_MA 注释： 运算符不会链接到前任，但是后继者可以链接到此运算符。
	 * The operator will not be chained to the predecessor, but successors may chain to this operator.
	 * // TODO_MA 注释： 如果当前这个 Function 是 SourceFunction
	 * // TODO_MA 注释： env.readFile()  env.socketTextStreawm()
	 */
	HEAD
}
