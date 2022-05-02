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

/**
 * // TODO_MA 注释： 用于设置和查询键控操作当前键的界面。
 * Inteface for setting and querying the current key of keyed operations.
 *
 * // TODO_MA 注释： 计时器系统主要在创建计时器时使用它查询键，并在触发计时器时设置正确的键上下文。
 * <p>This is mainly used by the timer system to query the key when creating timers
 * and to set the correct key context when firing a timer.
 */
public interface KeyContext {

	void setCurrentKey(Object key);

	Object getCurrentKey();
}
