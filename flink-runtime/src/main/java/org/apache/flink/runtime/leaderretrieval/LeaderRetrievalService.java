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

package org.apache.flink.runtime.leaderretrieval;

/**
 * // TODO_MA 注释： 此接口必须由服务实现，该服务可检索当前的领导者并将其通知给侦听器。
 * This interface has to be implemented by a service which retrieves the current leader and notifies a listener about it.
 *
 * // TODO_MA 注释： 在使用此服务之前，必须通过调用start方法将其启动。
 * // TODO_MA 注释： 起始方法还将{@link LeaderRetrievalListener}用作参数。该服务只能启动一次。
 * <p>Prior to using this service it has to be started by calling the start method. The start method
 * also takes the {@link LeaderRetrievalListener} as an argument. The service can only be started once.
 *
 * // TODO_MA 注释： 应该通过调用stop方法来停止服务。
 * <p>The service should be stopped by calling the stop method.
 */
public interface LeaderRetrievalService {

	/**
	 * Starts the leader retrieval service with the given listener to listen for new leaders. This
	 * method can only be called once.
	 *
	 * @param listener The leader retrieval listener which will be notified about new leaders.
	 * @throws Exception
	 */
	void start(LeaderRetrievalListener listener) throws Exception;

	/**
	 * Stops the leader retrieval service.
	 *
	 * @throws Exception
	 */
	void stop() throws Exception;
}
