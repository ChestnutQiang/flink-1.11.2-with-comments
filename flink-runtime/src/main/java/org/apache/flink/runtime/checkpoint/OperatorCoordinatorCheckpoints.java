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

import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.operators.coordination.OperatorCoordinator;
import org.apache.flink.runtime.operators.coordination.OperatorInfo;
import org.apache.flink.runtime.state.memory.ByteStreamStateHandle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * All the logic related to taking checkpoints of the {@link OperatorCoordinator}s.
 *
 * <p>NOTE: This class has a simplified error handling logic. If one of the several coordinator checkpoints
 * fail, no cleanup is triggered for the other concurrent ones. That is okay, since they all produce just byte[]
 * as the result. We have to change that once we allow then to create external resources that actually need
 * to be cleaned up.
 */
final class OperatorCoordinatorCheckpoints {

	public static CompletableFuture<CoordinatorSnapshot> triggerCoordinatorCheckpoint(
		final OperatorCoordinatorCheckpointContext coordinatorContext, final long checkpointId) throws Exception {

		final CompletableFuture<byte[]> checkpointFuture = new CompletableFuture<>();

		/*************************************************
		 *
		 *  注释： 检查 CoordinatorCheckpoint
		 */
		coordinatorContext.checkpointCoordinator(checkpointId, checkpointFuture);

		return checkpointFuture.thenApply((state) -> new CoordinatorSnapshot(coordinatorContext,
			new ByteStreamStateHandle(coordinatorContext.operatorId().toString(), state)));
	}

	public static CompletableFuture<AllCoordinatorSnapshots> triggerAllCoordinatorCheckpoints(
		final Collection<OperatorCoordinatorCheckpointContext> coordinators, final long checkpointId) throws Exception {

		final Collection<CompletableFuture<CoordinatorSnapshot>> individualSnapshots = new ArrayList<>(coordinators.size());

		for(final OperatorCoordinatorCheckpointContext coordinator : coordinators) {

			/*************************************************
			 *
			 *  注释：
			 */
			final CompletableFuture<CoordinatorSnapshot> checkpointFuture = triggerCoordinatorCheckpoint(coordinator, checkpointId);
			individualSnapshots.add(checkpointFuture);
		}

		return FutureUtils.combineAll(individualSnapshots).thenApply(AllCoordinatorSnapshots::new);
	}

	public static CompletableFuture<Void> triggerAndAcknowledgeAllCoordinatorCheckpoints(
		final Collection<OperatorCoordinatorCheckpointContext> coordinators, final PendingCheckpoint checkpoint,
		final Executor acknowledgeExecutor) throws Exception {

		/*************************************************
		 *
		 *  注释： trigger AllCoordinatorCheckpoints
		 */
		final CompletableFuture<AllCoordinatorSnapshots> snapshots = triggerAllCoordinatorCheckpoints(coordinators,
			checkpoint.getCheckpointId());

		return snapshots.thenAcceptAsync((allSnapshots) -> {
			try {
				/*************************************************
				 *
				 *  注释： ack AllCoordinators
				 */
				acknowledgeAllCoordinators(checkpoint, allSnapshots.snapshots);
			} catch(Exception e) {
				throw new CompletionException(e);
			}
		}, acknowledgeExecutor);
	}

	public static CompletableFuture<Void> triggerAndAcknowledgeAllCoordinatorCheckpointsWithCompletion(
		final Collection<OperatorCoordinatorCheckpointContext> coordinators, final PendingCheckpoint checkpoint,
		final Executor acknowledgeExecutor) throws CompletionException {

		try {

			/*************************************************
			 *
			 *  注释：
			 */
			return triggerAndAcknowledgeAllCoordinatorCheckpoints(coordinators, checkpoint, acknowledgeExecutor);
		} catch(Exception e) {
			throw new CompletionException(e);
		}
	}

	// ------------------------------------------------------------------------

	private static void acknowledgeAllCoordinators(PendingCheckpoint checkpoint,
		Collection<CoordinatorSnapshot> snapshots) throws CheckpointException {

		// TODO_MA 注释： 遍历收到的所有 CoordinatorSnapshot
		for(final CoordinatorSnapshot snapshot : snapshots) {

			// TODO_MA 注释： 接收到一个 Operator 的 CoordinatorStat ack
			final PendingCheckpoint.TaskAcknowledgeResult result = checkpoint.acknowledgeCoordinatorState(snapshot.coordinator, snapshot.state);

			// TODO_MA 注释： 如果不是返回：SUCCESS， 那就意味着 checkpoint 失败
			if(result != PendingCheckpoint.TaskAcknowledgeResult.SUCCESS) {
				final String errorMessage = "Coordinator state not acknowledged successfully: " + result;
				final Throwable error = checkpoint.isDiscarded() ? checkpoint.getFailureCause() : null;

				if(error != null) {
					throw new CheckpointException(errorMessage, CheckpointFailureReason.TRIGGER_CHECKPOINT_FAILURE, error);
				} else {
					throw new CheckpointException(errorMessage, CheckpointFailureReason.TRIGGER_CHECKPOINT_FAILURE);
				}
			}
		}
	}

	// ------------------------------------------------------------------------

	static final class AllCoordinatorSnapshots {

		private final Collection<CoordinatorSnapshot> snapshots;

		AllCoordinatorSnapshots(Collection<CoordinatorSnapshot> snapshots) {
			this.snapshots = snapshots;
		}

		public Iterable<CoordinatorSnapshot> snapshots() {
			return snapshots;
		}
	}

	static final class CoordinatorSnapshot {

		final OperatorInfo coordinator;
		final ByteStreamStateHandle state;

		CoordinatorSnapshot(OperatorInfo coordinator, ByteStreamStateHandle state) {
			this.coordinator = coordinator;
			this.state = state;
		}
	}
}
