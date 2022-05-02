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

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.entrypoint.ClusterInformation;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.io.network.partition.ResourceManagerPartitionTrackerFactory;
import org.apache.flink.runtime.metrics.groups.ResourceManagerMetricGroup;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * A standalone implementation of the resource manager. Used when the system is started in
 * standalone mode (via scripts), rather than via a resource framework like YARN or Mesos.
 *
 * <p>This ResourceManager doesn't acquire new resources.
 */
public class StandaloneResourceManager extends ResourceManager<ResourceID> {

	/**
	 * The duration of the startup period. A duration of zero means there is no startup period.
	 */
	private final Time startupPeriodTime;

	public StandaloneResourceManager(RpcService rpcService, ResourceID resourceId, HighAvailabilityServices highAvailabilityServices,
		HeartbeatServices heartbeatServices, SlotManager slotManager, ResourceManagerPartitionTrackerFactory clusterPartitionTrackerFactory,
		JobLeaderIdService jobLeaderIdService, ClusterInformation clusterInformation, FatalErrorHandler fatalErrorHandler,
		ResourceManagerMetricGroup resourceManagerMetricGroup, Time startupPeriodTime, Time rpcTimeout) {

		/*************************************************
		 *
		 *  注释： 注意该父类方法
		 */
		super(rpcService, resourceId, highAvailabilityServices, heartbeatServices, slotManager, clusterPartitionTrackerFactory, jobLeaderIdService,
			clusterInformation, fatalErrorHandler, resourceManagerMetricGroup, rpcTimeout);

		// TODO_MA 注释：
		this.startupPeriodTime = Preconditions.checkNotNull(startupPeriodTime);
	}

	@Override
	protected void initialize() throws ResourceManagerException {
		// nothing to initialize
	}

	@Override
	protected void internalDeregisterApplication(ApplicationStatus finalStatus, @Nullable String diagnostics) {
	}

	/*************************************************
	 *
	 *  注释： startNewWorker 和 stopWorker 这两个抽象方法是实现动态申请和释放资源的关键。
	 *  对于 Standalone 模式而言， TaskExecutor 是固定的，不支持动态启动和释放；而对于在 Yarn 上运行的 Flink，
	 *  YarnResourceManager 中这两个方法的具体实现就涉及到启动新的 container 和释放已经申请的 container。
	 */
	@Override
	public boolean startNewWorker(WorkerResourceSpec workerResourceSpec) {

		// TODO_MA 注释： StandAlone 并不会做到想启动 TaskExecutor 就能多启动一个的
		return false;
	}

	@Override
	public boolean stopWorker(ResourceID resourceID) {

		// TODO_MA 注释： 只有当在 YARN 集群中运行的时候，需要 释放 TaskExecutor
		// standalone resource manager cannot stop workers
		return false;
	}

	@Override
	protected ResourceID workerStarted(ResourceID resourceID) {
		return resourceID;
	}

	@Override
	protected void startServicesOnLeadership() {
		super.startServicesOnLeadership();
		startStartupPeriod();
	}

	private void startStartupPeriod() {
		setFailUnfulfillableRequest(false);

		final long startupPeriodMillis = startupPeriodTime.toMilliseconds();

		if(startupPeriodMillis > 0) {
			scheduleRunAsync(() -> setFailUnfulfillableRequest(true), startupPeriodMillis, TimeUnit.MILLISECONDS);
		}
	}
}
