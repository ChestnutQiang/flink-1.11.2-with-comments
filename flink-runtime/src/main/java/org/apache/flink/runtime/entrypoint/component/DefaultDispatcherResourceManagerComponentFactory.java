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

package org.apache.flink.runtime.entrypoint.component;

import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.blob.BlobServer;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.dispatcher.ArchivedExecutionGraphStore;
import org.apache.flink.runtime.dispatcher.DispatcherGateway;
import org.apache.flink.runtime.dispatcher.DispatcherId;
import org.apache.flink.runtime.dispatcher.HistoryServerArchivist;
import org.apache.flink.runtime.dispatcher.PartialDispatcherServices;
import org.apache.flink.runtime.dispatcher.SessionDispatcherFactory;
import org.apache.flink.runtime.dispatcher.runner.DefaultDispatcherRunnerFactory;
import org.apache.flink.runtime.dispatcher.runner.DispatcherRunner;
import org.apache.flink.runtime.dispatcher.runner.DispatcherRunnerFactory;
import org.apache.flink.runtime.entrypoint.ClusterInformation;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.jobmanager.HaServicesJobGraphStoreFactory;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.metrics.util.MetricUtils;
import org.apache.flink.runtime.resourcemanager.ResourceManager;
import org.apache.flink.runtime.resourcemanager.ResourceManagerFactory;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.rest.JobRestEndpointFactory;
import org.apache.flink.runtime.rest.RestEndpointFactory;
import org.apache.flink.runtime.rest.SessionRestEndpointFactory;
import org.apache.flink.runtime.rest.handler.legacy.metrics.MetricFetcher;
import org.apache.flink.runtime.rest.handler.legacy.metrics.MetricFetcherImpl;
import org.apache.flink.runtime.rest.handler.legacy.metrics.VoidMetricFetcher;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.RpcUtils;
import org.apache.flink.runtime.webmonitor.WebMonitorEndpoint;
import org.apache.flink.runtime.webmonitor.retriever.LeaderGatewayRetriever;
import org.apache.flink.runtime.webmonitor.retriever.MetricQueryServiceRetriever;
import org.apache.flink.runtime.webmonitor.retriever.impl.RpcGatewayRetriever;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Abstract class which implements the creation of the {@link DispatcherResourceManagerComponent} components.
 */
public class DefaultDispatcherResourceManagerComponentFactory implements DispatcherResourceManagerComponentFactory {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Nonnull
	private final DispatcherRunnerFactory dispatcherRunnerFactory;

	@Nonnull
	private final ResourceManagerFactory<?> resourceManagerFactory;

	@Nonnull
	private final RestEndpointFactory<?> restEndpointFactory;

	public DefaultDispatcherResourceManagerComponentFactory(
		@Nonnull DispatcherRunnerFactory dispatcherRunnerFactory,
		@Nonnull ResourceManagerFactory<?> resourceManagerFactory,
		@Nonnull RestEndpointFactory<?> restEndpointFactory) {

		// TODO_MA 注释： dispatcherRunnerFactory = DefaultDispatcherRunnerFactory
		this.dispatcherRunnerFactory = dispatcherRunnerFactory;

		// TODO_MA 注释： resourceManagerFactory = StandaloneResourceManagerFactory
		this.resourceManagerFactory = resourceManagerFactory;

		// TODO_MA 注释： restEndpointFactory = SessionRestEndpointFactory
		this.restEndpointFactory = restEndpointFactory;
	}

	/*************************************************
	 *
	 *  注释： dispatcherResourceManagerComponent，包含6个服务：
	 *  1. Dispatcher: 负责用于接收作业提交，持久化它们，生成要执行的作业管理器任务，并在主任务失败时恢复它们。此外,它知道关于 Flink 会话集群的状态。
	 *  2. ResourceManager: 负责资源的分配和记帐。
	 *                      registerJobManager(JobMasterId, ResourceID, String, JobID, Time)负责注册jobmaster,
	 *                      requestSlot(JobMasterId, SlotRequest, Time)从资源管理器请求一个槽
	 *  3. WebMonitorEndpoint: 服务于web前端Rest调用的Rest端点
	 *  4. dispatcherLeaderRetrievalService: 检索当前dispatcher leader并进行通知一个倾听者的服务:dispatcherGatewayRetriever
	 *  5. resourceManagerRetrievalService: 检索当前resourceManager leader并进行通知一个倾听者的服务:resourceManagerGatewayRetriever
	 *  6. partialDispatcherServices
	 */
	@Override
	public DispatcherResourceManagerComponent create(Configuration configuration, Executor ioExecutor, RpcService rpcService,
		HighAvailabilityServices highAvailabilityServices, BlobServer blobServer, HeartbeatServices heartbeatServices, MetricRegistry metricRegistry,
		ArchivedExecutionGraphStore archivedExecutionGraphStore, MetricQueryServiceRetriever metricQueryServiceRetriever,
		FatalErrorHandler fatalErrorHandler) throws Exception {

		// TODO_MA 注释： 检索当前leader并进行通知一个倾听者的服务
		LeaderRetrievalService dispatcherLeaderRetrievalService = null;
		// TODO_MA 注释： 检索当前leader并进行通知一个倾听者的服务
		LeaderRetrievalService resourceManagerRetrievalService = null;

		// TODO_MA 注释： 服务于web前端Rest调用的Rest端点
		WebMonitorEndpoint<?> webMonitorEndpoint = null;
		// TODO_MA 注释： ResourceManager实现。资源管理器负责资源的分配和记帐
		ResourceManager<?> resourceManager = null;
		// TODO_MA 注释： 封装Dispatcher如何执行的
		DispatcherRunner dispatcherRunner = null;

		try {

			// TODO_MA 注释： 用于 Dispatcher leader 选举
			// TODO_MA 注释： dispatcherLeaderRetrievalService = ZooKeeperLeaderRetrievalService
			dispatcherLeaderRetrievalService = highAvailabilityServices.getDispatcherLeaderRetriever();

			// TODO_MA 注释： 用于 ResourceManager leader 选举
			// TODO_MA 注释： resourceManagerRetrievalService = ZooKeeperLeaderRetrievalService
			resourceManagerRetrievalService = highAvailabilityServices.getResourceManagerLeaderRetriever();

			// TODO_MA 注释： Dispatcher 的 Gateway
			final LeaderGatewayRetriever<DispatcherGateway> dispatcherGatewayRetriever = new RpcGatewayRetriever<>(rpcService,
				DispatcherGateway.class, DispatcherId::fromUuid, 10, Time.milliseconds(50L));
			// TODO_MA 注释： ResourceManager 的 Gateway
			final LeaderGatewayRetriever<ResourceManagerGateway> resourceManagerGatewayRetriever = new RpcGatewayRetriever<>(rpcService,
				ResourceManagerGateway.class, ResourceManagerId::fromUuid, 10, Time.milliseconds(50L));

			// TODO_MA 注释： 创建线程池，用于执行 WebMonitorEndpoint 所接收到的 client 发送过来的请求
			final ScheduledExecutorService executor = WebMonitorEndpoint
				.createExecutorService(configuration.getInteger(RestOptions.SERVER_NUM_THREADS),
					configuration.getInteger(RestOptions.SERVER_THREAD_PRIORITY), "DispatcherRestEndpoint");

			// TODO_MA 注释： 初始化 MetricFetcher
			final long updateInterval = configuration.getLong(MetricOptions.METRIC_FETCHER_UPDATE_INTERVAL);
			final MetricFetcher metricFetcher = updateInterval == 0 ? VoidMetricFetcher.INSTANCE : MetricFetcherImpl
				.fromConfiguration(configuration, metricQueryServiceRetriever, dispatcherGatewayRetriever, executor);

			/*************************************************
			 *
			 *  注释： 创建 WebMonitorEndpoint 实例， 在 Standalone模式下：DispatcherRestEndpoint
			 *  1、restEndpointFactory = SessionRestEndpointFactory
			 *  2、webMonitorEndpoint = DispatcherRestEndpoint
			 *  3、highAvailabilityServices.getClusterRestEndpointLeaderElectionService() = ZooKeeperLeaderElectionService
			 */
			webMonitorEndpoint = restEndpointFactory
				.createRestEndpoint(configuration, dispatcherGatewayRetriever, resourceManagerGatewayRetriever, blobServer, executor, metricFetcher,
					highAvailabilityServices.getClusterRestEndpointLeaderElectionService(), fatalErrorHandler);

			/*************************************************
			 *
			 *  注释： 启动 DispatcherRestEndpoint
			 *  1、启动 Netty 服务端
			 *  2、选举
			 *  3、启动定时任务 ExecutionGraphCacheCleanupTask
			 */
			log.debug("Starting Dispatcher REST endpoint.");
			webMonitorEndpoint.start();

			final String hostname = RpcUtils.getHostname(rpcService);

			/*************************************************
			 *
			 *  注释： 创建 StandaloneResourceManager 实例对象
			 *  1、resourceManager = StandaloneResourceManager
			 *  2、resourceManagerFactory = StandaloneResourceManagerFactory
			 */
			resourceManager = resourceManagerFactory
				.createResourceManager(configuration, ResourceID.generate(), rpcService, highAvailabilityServices, heartbeatServices,
					fatalErrorHandler, new ClusterInformation(hostname, blobServer.getPort()), webMonitorEndpoint.getRestBaseUrl(), metricRegistry,
					hostname);

			final HistoryServerArchivist historyServerArchivist = HistoryServerArchivist
				.createHistoryServerArchivist(configuration, webMonitorEndpoint, ioExecutor);

			final PartialDispatcherServices partialDispatcherServices = new PartialDispatcherServices(configuration, highAvailabilityServices,
				resourceManagerGatewayRetriever, blobServer, heartbeatServices,
				() -> MetricUtils.instantiateJobManagerMetricGroup(metricRegistry, hostname), archivedExecutionGraphStore, fatalErrorHandler,
				historyServerArchivist, metricRegistry.getMetricQueryServiceGatewayRpcAddress());

			/*************************************************
			 *
			 *  注释： 创建 并启动 Dispatcher
			 *  1、dispatcherRunner = DispatcherRunnerLeaderElectionLifecycleManager
			 *  2、dispatcherRunnerFactory = DefaultDispatcherRunnerFactory
			 *  第一个参数： ZooKeeperLeaderElectionService
			 *  -
			 *  老版本： 这个地方是直接创建一个 Dispatcher 对象然后调用 dispatcher.start() 来启动
			 *  新版本： 直接创建一个 DispatcherRunner， 内部就是要创建和启动 Dispatcher
			 */
			log.debug("Starting Dispatcher.");
			dispatcherRunner = dispatcherRunnerFactory
				.createDispatcherRunner(highAvailabilityServices.getDispatcherLeaderElectionService(), fatalErrorHandler,

					// TODO_MA 注释： 注意第三个参数
					new HaServicesJobGraphStoreFactory(highAvailabilityServices), ioExecutor, rpcService, partialDispatcherServices);

			/*************************************************
			 *
			 *  注释： resourceManager 启动
			 */
			log.debug("Starting ResourceManager.");
			resourceManager.start();

			/*************************************************
			 *
			 *  注释： resourceManagerRetrievalService 启动
			 */
			resourceManagerRetrievalService.start(resourceManagerGatewayRetriever);

			/*************************************************
			 *
			 *  注释： ZooKeeperHaServices 启动
			 */
			dispatcherLeaderRetrievalService.start(dispatcherGatewayRetriever);

			/*************************************************
			 *
			 *  注释： 构建 DispatcherResourceManagerComponent
			 */
			return new DispatcherResourceManagerComponent(dispatcherRunner, resourceManager, dispatcherLeaderRetrievalService,
				resourceManagerRetrievalService, webMonitorEndpoint);

		} catch(Exception exception) {
			// clean up all started components
			if(dispatcherLeaderRetrievalService != null) {
				try {
					dispatcherLeaderRetrievalService.stop();
				} catch(Exception e) {
					exception = ExceptionUtils.firstOrSuppressed(e, exception);
				}
			}

			if(resourceManagerRetrievalService != null) {
				try {
					resourceManagerRetrievalService.stop();
				} catch(Exception e) {
					exception = ExceptionUtils.firstOrSuppressed(e, exception);
				}
			}

			final Collection<CompletableFuture<Void>> terminationFutures = new ArrayList<>(3);

			if(webMonitorEndpoint != null) {
				terminationFutures.add(webMonitorEndpoint.closeAsync());
			}

			if(resourceManager != null) {
				terminationFutures.add(resourceManager.closeAsync());
			}

			if(dispatcherRunner != null) {
				terminationFutures.add(dispatcherRunner.closeAsync());
			}

			final FutureUtils.ConjunctFuture<Void> terminationFuture = FutureUtils.completeAll(terminationFutures);

			try {
				terminationFuture.get();
			} catch(Exception e) {
				exception = ExceptionUtils.firstOrSuppressed(e, exception);
			}

			throw new FlinkException("Could not create the DispatcherResourceManagerComponent.", exception);
		}
	}

	public static DefaultDispatcherResourceManagerComponentFactory createSessionComponentFactory(ResourceManagerFactory<?> resourceManagerFactory) {

		/*************************************************
		 *
		 *  注释：
		 *  1、resourceManagerFactory = StandaloneResourceManagerFactory
		 *  2、dispatcherRunnerFactory = DefaultDispatcherRunnerFactory
		 *  3、restEndpointFactory = SessionRestEndpointFactory
		 */
		return new DefaultDispatcherResourceManagerComponentFactory(
			DefaultDispatcherRunnerFactory.createSessionRunner(SessionDispatcherFactory.INSTANCE), resourceManagerFactory,
			SessionRestEndpointFactory.INSTANCE);
	}

	public static DefaultDispatcherResourceManagerComponentFactory createJobComponentFactory(ResourceManagerFactory<?> resourceManagerFactory,
		JobGraphRetriever jobGraphRetriever) {
		return new DefaultDispatcherResourceManagerComponentFactory(DefaultDispatcherRunnerFactory.createJobRunner(jobGraphRetriever),
			resourceManagerFactory, JobRestEndpointFactory.INSTANCE);
	}
}
