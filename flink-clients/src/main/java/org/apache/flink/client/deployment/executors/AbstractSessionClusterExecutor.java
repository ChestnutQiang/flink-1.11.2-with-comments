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

package org.apache.flink.client.deployment.executors;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.dag.Pipeline;
import org.apache.flink.client.deployment.ClusterClientFactory;
import org.apache.flink.client.deployment.ClusterClientJobClientAdapter;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.ClusterClientProvider;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.PipelineExecutor;
import org.apache.flink.runtime.jobgraph.JobGraph;

import javax.annotation.Nonnull;

import java.util.concurrent.CompletableFuture;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An abstract {@link PipelineExecutor} used to execute {@link Pipeline pipelines} on an existing (session) cluster.
 *
 * @param <ClusterID>     the type of the id of the cluster.
 * @param <ClientFactory> the type of the {@link ClusterClientFactory} used to create/retrieve a client to the target cluster.
 */
@Internal
public class AbstractSessionClusterExecutor<ClusterID, ClientFactory extends ClusterClientFactory<ClusterID>> implements PipelineExecutor {

	/*************************************************
	 *
	 *  注释： StandaloneClientFactory = clusterClientFactory
	 */
	private final ClientFactory clusterClientFactory;

	public AbstractSessionClusterExecutor(@Nonnull final ClientFactory clusterClientFactory) {
		this.clusterClientFactory = checkNotNull(clusterClientFactory);
	}

	@Override
	public CompletableFuture<JobClient> execute(@Nonnull final Pipeline pipeline, @Nonnull final Configuration configuration) throws Exception {

		/*************************************************
		 *
		 *  注释： 获取 JobGraph
		 *  pipeline 其实就是 StreamGraph
		 */
		final JobGraph jobGraph = PipelineExecutorUtils.getJobGraph(pipeline, configuration);

		/*************************************************
		 *
		 *  注释： clusterDescriptor = StandaloneClusterDescriptor
		 */
		try(final ClusterDescriptor<ClusterID> clusterDescriptor = clusterClientFactory.createClusterDescriptor(configuration)) {
			final ClusterID clusterID = clusterClientFactory.getClusterId(configuration);
			checkState(clusterID != null);

			// TODO_MA 注释： 用于创建 RestClusterClient 的Provider: ClusterClientProvider
			final ClusterClientProvider<ClusterID> clusterClientProvider = clusterDescriptor.retrieve(clusterID);

			// TODO_MA 注释：clusterClient = RestClusterClient
			ClusterClient<ClusterID> clusterClient = clusterClientProvider.getClusterClient();

			/*************************************************
			 *
			 *  注释： 提交执行
			 *  1、MiniClusterClient 本地执行
			 *  2、RestClusterClient 提交到 Flink Rest 服务接收处理
			 */
			return clusterClient.submitJob(jobGraph)
				.thenApplyAsync(jobID -> (JobClient) new ClusterClientJobClientAdapter<>(clusterClientProvider, jobID))
				.whenComplete((ignored1, ignored2) -> clusterClient.close());
		}
	}
}
