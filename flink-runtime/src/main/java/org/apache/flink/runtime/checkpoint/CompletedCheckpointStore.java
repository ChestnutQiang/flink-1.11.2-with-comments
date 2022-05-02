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

import org.apache.flink.api.common.JobStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ListIterator;

/**
 * A bounded LIFO-queue of {@link CompletedCheckpoint} instances.
 */
public interface CompletedCheckpointStore {

	Logger LOG = LoggerFactory.getLogger(CompletedCheckpointStore.class);

	/**
	 * Recover available {@link CompletedCheckpoint} instances.
	 *
	 * <p>After a call to this method, {@link #getLatestCheckpoint(boolean)} returns the latest
	 * available checkpoint.
	 */
	void recover() throws Exception;

	/**
	 * Adds a {@link CompletedCheckpoint} instance to the list of completed checkpoints.
	 *
	 * <p>Only a bounded number of checkpoints is kept. When exceeding the maximum number of
	 * retained checkpoints, the oldest one will be discarded.
	 */
	void addCheckpoint(CompletedCheckpoint checkpoint) throws Exception;

	/**
	 * // TODO_MA 注释： 返回最新的 {@link CompletedCheckpoint} 实例；如果未添加，则返回 null
	 * Returns the latest {@link CompletedCheckpoint} instance or <code>null</code> if none was added.
	 */
	default CompletedCheckpoint getLatestCheckpoint(boolean isPreferCheckpointForRecovery) throws Exception {

		// TODO_MA 注释： 获取到 所有 checkpoint
		List<CompletedCheckpoint> allCheckpoints = getAllCheckpoints();

		// TODO_MA 注释： 如果该结果集为空，则直接返回 null
		if (allCheckpoints.isEmpty()) {
			return null;
		}

		// TODO_MA 注释： 先获取最近一个
		CompletedCheckpoint lastCompleted = allCheckpoints.get(allCheckpoints.size() - 1);

		/*************************************************
		 * 
		 *  注释： 进行判断
		 */
		if (isPreferCheckpointForRecovery && allCheckpoints.size() > 1 && lastCompleted.getProperties().isSavepoint()) {

			// TODO_MA 注释： 生成 List 迭代器
			ListIterator<CompletedCheckpoint> listIterator = allCheckpoints.listIterator(allCheckpoints.size() - 1);

			// TODO_MA 注释： 进行迭代
			while (listIterator.hasPrevious()) {
				CompletedCheckpoint prev = listIterator.previous();

				// TODO_MA 注释： 判断，只要该 savePoint 是完整的，就是可用的
				if (!prev.getProperties().isSavepoint()) {
					LOG.info("Found a completed checkpoint ({}) before the latest savepoint, will use it to recover!", prev);
					return prev;
				}
			}
			LOG.info("Did not find earlier checkpoint, using latest savepoint to recover.");
		}

		return lastCompleted;
	}

	/**
	 * Shuts down the store.
	 *
	 * <p>The job status is forwarded and used to decide whether state should
	 * actually be discarded or kept.
	 *
	 * @param jobStatus Job state on shut down
	 */
	void shutdown(JobStatus jobStatus) throws Exception;

	/**
	 * Returns all {@link CompletedCheckpoint} instances.
	 *
	 * <p>Returns an empty list if no checkpoint has been added yet.
	 */
	List<CompletedCheckpoint> getAllCheckpoints() throws Exception;

	/**
	 * Returns the current number of retained checkpoints.
	 */
	int getNumberOfRetainedCheckpoints();

	/**
	 * Returns the max number of retained checkpoints.
	 */
	int getMaxNumberOfRetainedCheckpoints();

	/**
	 * This method returns whether the completed checkpoint store requires checkpoints to be
	 * externalized. Externalized checkpoints have their meta data persisted, which the checkpoint
	 * store can exploit (for example by simply pointing the persisted metadata).
	 *
	 * @return True, if the store requires that checkpoints are externalized before being added, false
	 *         if the store stores the metadata itself.
	 */
	boolean requiresExternalizedCheckpoints();

	static CompletedCheckpointStore storeFor(CompletedCheckpoint... checkpoints) throws Exception {
		StandaloneCompletedCheckpointStore store = new StandaloneCompletedCheckpointStore(checkpoints.length);
		for (final CompletedCheckpoint checkpoint : checkpoints) {
			store.addCheckpoint(checkpoint);
		}
		return store;
	}
}
