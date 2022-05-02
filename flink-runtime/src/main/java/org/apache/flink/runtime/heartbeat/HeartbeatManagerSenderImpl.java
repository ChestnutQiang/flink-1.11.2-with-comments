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

package org.apache.flink.runtime.heartbeat;

import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * {@link HeartbeatManager} implementation which regularly requests a heartbeat response from
 * its monitored {@link HeartbeatTarget}. The heartbeat period is configurable.
 *
 * @param <I> Type of the incoming heartbeat payload
 * @param <O> Type of the outgoing heartbeat payload
 */
public class HeartbeatManagerSenderImpl<I, O> extends HeartbeatManagerImpl<I, O> implements Runnable {

	private final long heartbeatPeriod;

	HeartbeatManagerSenderImpl(long heartbeatPeriod, long heartbeatTimeout, ResourceID ownResourceID, HeartbeatListener<I, O> heartbeatListener,
		ScheduledExecutor mainThreadExecutor, Logger log) {

		// TODO_MA 注释： 调用重载构造
		this(heartbeatPeriod, heartbeatTimeout, ownResourceID, heartbeatListener, mainThreadExecutor, log, new HeartbeatMonitorImpl.Factory<>());
	}

	HeartbeatManagerSenderImpl(long heartbeatPeriod, long heartbeatTimeout, ResourceID ownResourceID, HeartbeatListener<I, O> heartbeatListener,
		ScheduledExecutor mainThreadExecutor, Logger log, HeartbeatMonitor.Factory<O> heartbeatMonitorFactory) {
		super(heartbeatTimeout, ownResourceID, heartbeatListener, mainThreadExecutor, log, heartbeatMonitorFactory);
		this.heartbeatPeriod = heartbeatPeriod;

		/*************************************************
		 *
		 *  注释： 调度当前的类实例的 run() 方法的执行
		 *  执行的就是当前类的 run() 方法
		 */
		mainThreadExecutor.schedule(this, 0L, TimeUnit.MILLISECONDS);
	}

	@Override
	public void run() {

		/*************************************************
		 *
		 *  注释： 在 Flink 的心跳机制中，跟其他的 集群不一样：
		 *  1、ResourceManager 发送心跳给 从节点 Taskmanager
		 *  2、从节点接收到心跳之后，返回响应
		 */

		// TODO_MA 注释： 实现循环执行
		if(!stopped) {

			/*************************************************
			 *
			 *  注释： 遍历每一个 TaskExecutor 出来，然后发送 心跳请求
			 *  每一次 TaskEXecutor 来 RM 注册的时候， 在注册成功之后，就会给这个 TaskEXecutor 生成一个
			 *  Target， 最终，这个 Target 被封装在 ： Monitor，
			 *  最终，每个 TaskEXecutor 对应的一个唯一的 Monitor 就被保存在 heartbeatTargets map 中
			 *  RM 在启动的时候，就已经启动了： TaskManagerHeartbeamManager
			 *  这个组件的内部： 启动了一个 HearBeatManagerSenderImpl 对象。
			 *  内部通过一种特别的机制，实现了一个 每隔 10s 调度一次 该组建的额 run 运行
			 *  最终的效果；
			 *  RM 启动好了之后，就每隔10s 钟，向所有的已注册的 TaskEXecutor 发送心跳请求
			 *  如果最终，发现某一个 TaskExecutor 的上一次心跳时间，举例现在超过 50s
			 *  则认为该 TaskExecutor 宕机了。 RM 要执行针对这个 TaskExecutor 的注销
			 */
			log.debug("Trigger heartbeat request.");
			for(HeartbeatMonitor<O> heartbeatMonitor : getHeartbeatTargets().values()) {

				// TODO_MA 注释： ResourceManager 给 目标发送（TaskManager 或者 JobManager） 心跳
				requestHeartbeat(heartbeatMonitor);
			}

			/*************************************************
			 *
			 *  注释： 实现循环发送心跳的效果
			 *  1、心跳时间：10s钟
			 *  2、心跳超时时间：50s钟
			 */
			getMainThreadExecutor().schedule(this, heartbeatPeriod, TimeUnit.MILLISECONDS);
		}
	}

	/*************************************************
	 *
	 *  注释： HeartbeatMonitor 如果有  从节点返回心跳响应，则会被加入到 HeartbeatMonitor
	 *  管理了所有的心跳目标对象
	 */
	private void requestHeartbeat(HeartbeatMonitor<O> heartbeatMonitor) {

		// TODO_MA 注释： 根据 heartbeatMonitor 获取 heartbeatTarget
		O payload = getHeartbeatListener().retrievePayload(heartbeatMonitor.getHeartbeatTargetId());
		final HeartbeatTarget<O> heartbeatTarget = heartbeatMonitor.getHeartbeatTarget();

		/*************************************************
		 *
		 *  注释： 发送心跳
		 *  其实就是 集群中启动的从节点
		 */
		heartbeatTarget.requestHeartbeat(getOwnResourceID(), payload);
	}
}
