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
import org.apache.flink.util.Preconditions;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default implementation of {@link HeartbeatMonitor}.
 *
 * @param <O> Type of the payload being sent to the associated heartbeat target
 */
public class HeartbeatMonitorImpl<O> implements HeartbeatMonitor<O>, Runnable {

	/** Resource ID of the monitored heartbeat target. */
	private final ResourceID resourceID;

	/** Associated heartbeat target. */
	private final HeartbeatTarget<O> heartbeatTarget;

	private final ScheduledExecutor scheduledExecutor;

	/** Listener which is notified about heartbeat timeouts. */
	private final HeartbeatListener<?, ?> heartbeatListener;

	/** Maximum heartbeat timeout interval. */
	private final long heartbeatTimeoutIntervalMs;

	private volatile ScheduledFuture<?> futureTimeout;

	private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

	private volatile long lastHeartbeat;

	HeartbeatMonitorImpl(
		ResourceID resourceID,
		HeartbeatTarget<O> heartbeatTarget,
		ScheduledExecutor scheduledExecutor,
		HeartbeatListener<?, O> heartbeatListener,
		long heartbeatTimeoutIntervalMs) {

		this.resourceID = Preconditions.checkNotNull(resourceID);
		this.heartbeatTarget = Preconditions.checkNotNull(heartbeatTarget);
		this.scheduledExecutor = Preconditions.checkNotNull(scheduledExecutor);

		// TODO_MA 注释： ResourceManagerHeartbeatListener
		this.heartbeatListener = Preconditions.checkNotNull(heartbeatListener);

		Preconditions.checkArgument(heartbeatTimeoutIntervalMs > 0L, "The heartbeat timeout interval has to be larger than 0.");
		this.heartbeatTimeoutIntervalMs = heartbeatTimeoutIntervalMs;

		lastHeartbeat = 0L;

		resetHeartbeatTimeout(heartbeatTimeoutIntervalMs);
	}

	@Override
	public HeartbeatTarget<O> getHeartbeatTarget() {
		return heartbeatTarget;
	}

	@Override
	public ResourceID getHeartbeatTargetId() {
		return resourceID;
	}

	@Override
	public long getLastHeartbeat() {
		return lastHeartbeat;
	}

	@Override
	public void reportHeartbeat() {

		// TODO_MA 注释： 更新最新心跳时间
		lastHeartbeat = System.currentTimeMillis();

		/*************************************************
		 * 
		 *  注释： 重设心跳超时相关的 时间 和 延迟调度任务
		 */
		resetHeartbeatTimeout(heartbeatTimeoutIntervalMs);

		/*************************************************
		 * 
		 *  注释： 最开始启动了一个注册超时检查！
		 */
	}

	@Override
	public void cancel() {
		// we can only cancel if we are in state running
		if (state.compareAndSet(State.RUNNING, State.CANCELED)) {
			cancelTimeout();
		}
	}

	@Override
	public void run() {
		// The heartbeat has timed out if we're in state running
		if (state.compareAndSet(State.RUNNING, State.TIMEOUT)) {

			/*************************************************
			 * 
			 *  注释： 调度 TaskManager 的下线
			 *  heartbeatListener = ResourceManagerHeartbeatListener
			 *  如果代码真正的能执行到这儿，就证明连续 5 次 ResourceManager 发送过来的心跳请求
			 *  当前这个 TaskExecutor 都没有收到了， 但是正常情况下， ResourceManager 肯定是存活的呀
			 *  所以： 发起跟 ResourceManager 的重新链接
			 */
			heartbeatListener.notifyHeartbeatTimeout(resourceID);
		}
	}

	public boolean isCanceled() {
		return state.get() == State.CANCELED;
	}

	/*************************************************
	 * 
	 *  注释： 关于FlinK 的主从节点的心跳：
	 *  1、首先启动 RM， 启动 HeartbeatManager， 每10s中，针对哪个注册的 TaskExecutor（遍历Map）
	 *     执行 发送心跳请求
	 *  2、再启动 TaskExecutor， 首先启动 超时检查任务（5min）
	 *     启动好了之后，会进行注册，接收到心跳请求请求之后，也就相当于 RM 和 TE 之间就维持了正常的心跳。
	 *  3、TaskExecutor 每次接收到 RM 的心跳请求之后，就重置自己的超时任务
	 */
	void resetHeartbeatTimeout(long heartbeatTimeout) {
		if (state.get() == State.RUNNING) {

			// TODO_MA 注释： 取消之前的 延迟调度任务
			cancelTimeout();

			/*************************************************
			 * 
			 *  注释： 重新调度
			 *  其实这个逻辑就是： 如果你不取消，那么 50s 后就执行这个延迟调度任务，执行 TaskManager 的下线操作
			 */
			futureTimeout = scheduledExecutor.schedule(this, heartbeatTimeout, TimeUnit.MILLISECONDS);

			// Double check for concurrent accesses (e.g. a firing of the scheduled future)
			if (state.get() != State.RUNNING) {
				cancelTimeout();
			}
		}
	}

	private void cancelTimeout() {
		if (futureTimeout != null) {

			// TODO_MA 注释： 取消
			futureTimeout.cancel(true);
		}
	}

	private enum State {
		RUNNING,
		TIMEOUT,
		CANCELED
	}

	/**
	 * The factory that instantiates {@link HeartbeatMonitorImpl}.
	 *
	 * @param <O> Type of the outgoing heartbeat payload
	 */
	static class Factory<O> implements HeartbeatMonitor.Factory<O> {

		@Override
		public HeartbeatMonitor<O> createHeartbeatMonitor(
			ResourceID resourceID,
			HeartbeatTarget<O> heartbeatTarget,
			ScheduledExecutor mainThreadExecutor,
			HeartbeatListener<?, O> heartbeatListener,
			long heartbeatTimeoutIntervalMs) {

			/*************************************************
			 * 
			 *  注释： 心跳管理器 实现类
			 */
			return new HeartbeatMonitorImpl<>(
				resourceID,
				heartbeatTarget,
				mainThreadExecutor,
				heartbeatListener,
				heartbeatTimeoutIntervalMs
			);
		}
	}
}
