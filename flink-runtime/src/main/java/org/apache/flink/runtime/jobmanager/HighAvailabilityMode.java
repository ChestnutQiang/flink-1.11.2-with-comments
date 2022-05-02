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

package org.apache.flink.runtime.jobmanager;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;

/**
 * // TODO_MA 注释： Flink HA 的实现目前是依赖了 ZK，使用 curator 这个包来实现的
 * High availability mode for Flink's cluster execution. Currently supported modes are:
 *
 * - NONE: No high availability.
 * - ZooKeeper: JobManager high availability via ZooKeeper
 * ZooKeeper is used to select a leader among a group of JobManager. This JobManager
 * is responsible for the job execution. Upon failure of the leader a new leader is elected
 * which will take over the responsibilities of the old leader
 * - FACTORY_CLASS: Use implementation of {@link org.apache.flink.runtime.highavailability.HighAvailabilityServicesFactory}
 * specified in configuration property high-availability
 */
public enum HighAvailabilityMode {
	NONE(false),
	ZOOKEEPER(true),
	FACTORY_CLASS(true);

	private final boolean haActive;

	HighAvailabilityMode(boolean haActive) {
		this.haActive = haActive;
	}

	/**
	 * Return the configured {@link HighAvailabilityMode}.
	 *
	 * @param config The config to parse
	 * @return Configured recovery mode or {@link HighAvailabilityMode#NONE} if not
	 * configured.
	 */
	public static HighAvailabilityMode fromConfig(Configuration config) {

		// TODO_MA 注释： 获取 高可用模式(high-availability = zookeeper)
		String haMode = config.getValue(HighAvailabilityOptions.HA_MODE);

		if (haMode == null) {
			return HighAvailabilityMode.NONE;
		} else if (haMode.equalsIgnoreCase(ConfigConstants.DEFAULT_RECOVERY_MODE)) {
			// Map old default to new default
			return HighAvailabilityMode.NONE;
		} else {
			try {
				// TODO_MA 注释： 返回正常配置值
				return HighAvailabilityMode.valueOf(haMode.toUpperCase());
			} catch (IllegalArgumentException e) {
				return FACTORY_CLASS;
			}
		}
	}

	/**
	 * Returns true if the defined recovery mode supports high availability.
	 *
	 * @param configuration Configuration which contains the recovery mode
	 * @return true if high availability is supported by the recovery mode, otherwise false
	 */
	public static boolean isHighAvailabilityModeActivated(Configuration configuration) {

		// TODO_MA 注释： 是否定义了 恢复模式 来支持高可用
		HighAvailabilityMode mode = fromConfig(configuration);
		return mode.haActive;
	}
}
