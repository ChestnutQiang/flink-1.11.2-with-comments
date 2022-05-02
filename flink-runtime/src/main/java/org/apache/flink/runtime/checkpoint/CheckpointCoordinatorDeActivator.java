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

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.runtime.executiongraph.JobStatusListener;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * // TODO_MA 注释： 在 ExecutionGraphBuilder#buildGraph() 方法中，如果作业开启了 checkpoint，
 * // TODO_MA 注释： 则会调用 ExecutionGraph.enableCheckpointing() 方法, 这里会创建 CheckpointCoordinator 对象，
 * // TODO_MA 注释： 并注册一个作业状态的监听 CheckpointCoordinatorDeActivator,
 * // TODO_MA 注释： CheckpointCoordinatorDeActivator 会在作业状态发生改变时得到通知。
 * // TODO_MA 注释： 当状态变为 RUNNING 时，CheckpointCoordinatorDeActivator 会得到通知，
 * // TODO_MA 注释： 并且通过 CheckpointCoordinator.startCheckpointScheduler 启动 checkpoint 的定时器。
 *
 * // TODO_MA 注释： 该参与者侦听JobStatus中的更改，并激活或停用定期检查点调度程序。
 * This actor listens to changes in the JobStatus and activates or deactivates the periodic checkpoint scheduler.
 */
public class CheckpointCoordinatorDeActivator implements JobStatusListener {

	private final CheckpointCoordinator coordinator;

	/*************************************************
	 *
	 *  注释：
	 *  CheckpointCoordinatorDeActivator 实际上是一个监听器，
	 *  当作业状态转化成 JobStatus.RUNNING 时，CheckpointCoordinator 中的调度器启动。
	 */
	public CheckpointCoordinatorDeActivator(CheckpointCoordinator coordinator) {
		this.coordinator = checkNotNull(coordinator);
	}

	/*************************************************
	 *
	 *  注释： 如果 Job 的状态发生改变，则会自动调用该方法
	 */
	@Override
	public void jobStatusChanges(JobID jobId, JobStatus newJobStatus, long timestamp, Throwable error) {

		/*************************************************
		 *
		 *  注释： 当 job 状态为  RUNNING 的时候，则开始调度 checkpoint 执行了
		 */
		if(newJobStatus == JobStatus.RUNNING) {

			/*************************************************
			 *
			 *  注释： 拉起 Checkpoint 的定时调度任务
			 */
			// start the checkpoint scheduler
			coordinator.startCheckpointScheduler();
		} else {
			// anything else should stop the trigger for now
			coordinator.stopCheckpointScheduler();
		}
	}
}
