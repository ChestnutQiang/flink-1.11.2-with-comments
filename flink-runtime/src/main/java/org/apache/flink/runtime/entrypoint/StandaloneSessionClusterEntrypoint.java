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

package org.apache.flink.runtime.entrypoint;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.entrypoint.component.DefaultDispatcherResourceManagerComponentFactory;
import org.apache.flink.runtime.entrypoint.parser.CommandLineParser;
import org.apache.flink.runtime.resourcemanager.StandaloneResourceManagerFactory;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.runtime.util.JvmShutdownSafeguard;
import org.apache.flink.runtime.util.SignalHandler;

/*************************************************
 *
 *  注释： flink有三种方式执行应用程序：session mode, per-job mode, applocation mode
 *  模型的区别主要包含：
 *  1. 集群生命周期和资源隔离保证
 *  2. 应用程序的main()方法是在客户机上执行还是在集群上执行
 */

/**
 * Entry point for the standalone session cluster.
 */
public class StandaloneSessionClusterEntrypoint extends SessionClusterEntrypoint {

	public StandaloneSessionClusterEntrypoint(Configuration configuration) {
		super(configuration);
	}

	@Override
	protected DefaultDispatcherResourceManagerComponentFactory createDispatcherResourceManagerComponentFactory(Configuration configuration) {
		/*************************************************
		 *
		 *  注释：
		 *  1、参数是：StandaloneResourceManagerFactory 实例
		 *  2、返回值：DefaultDispatcherResourceManagerComponentFactory 实例
		 */
		return DefaultDispatcherResourceManagerComponentFactory
			.createSessionComponentFactory(StandaloneResourceManagerFactory.getInstance());
	}

	/*************************************************
	 *
	 *  注释： 入口
	 */
	public static void main(String[] args) {

		// TODO_MA 注释：提供对 JVM 执行环境的访问的实用程序类，如执行用户(getHadoopUser())、启动选项或JVM版本。
		// startup checks and logging
		EnvironmentInformation.logEnvironmentInfo(LOG, StandaloneSessionClusterEntrypoint.class.getSimpleName(), args);

		// TODO_MA 注释：注册一些信号处理
		SignalHandler.register(LOG);

		// TODO_MA 注释： 安装安全关闭的钩子
		// TODO_MA 注释： 你的 Flink集群启动过程中，或者在启动好了之后的运行中，
		// TODO_MA 注释： 都有可能接收到关闭集群的命令
		JvmShutdownSafeguard.installAsShutdownHook(LOG);

		EntrypointClusterConfiguration entrypointClusterConfiguration = null;

		// TODO_MA 注释：
		final CommandLineParser<EntrypointClusterConfiguration> commandLineParser = new CommandLineParser<>(
			new EntrypointClusterConfigurationParserFactory());

		try {

			/*************************************************
			 *
			 *  注释： 对传入的参数进行解析
			 *  内部通过 EntrypointClusterConfigurationParserFactory 解析配置文件，
			 *  返回 EntrypointClusterConfiguration 为 ClusterConfiguration 的子类
			 */
			entrypointClusterConfiguration = commandLineParser.parse(args);

		} catch(FlinkParseException e) {
			LOG.error("Could not parse command line arguments {}.", args, e);
			commandLineParser.printHelp(StandaloneSessionClusterEntrypoint.class.getSimpleName());
			System.exit(1);
		}

		/*************************************************
		 *
		 *  注释： 解析配置参数, 解析 flink 的配置文件： fink-conf.ymal
		 */
		Configuration configuration = loadConfiguration(entrypointClusterConfiguration);

		/*************************************************
		 *
		 *  注释：创建 StandaloneSessionClusterEntrypoint
		 */
		StandaloneSessionClusterEntrypoint entrypoint = new StandaloneSessionClusterEntrypoint(configuration);

		/*************************************************
		 *
		 *  注释：启动集群的entrypoint
		 *  这个方法接受的是父类 ClusterEntrypoint，可想而知其他几种启动方式也是通过这个方法。
		 */
		ClusterEntrypoint.runClusterEntrypoint(entrypoint);
	}
}
