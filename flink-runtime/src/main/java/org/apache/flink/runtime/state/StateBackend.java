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

package org.apache.flink.runtime.state;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.query.TaskKvStateRegistry;
import org.apache.flink.runtime.state.ttl.TtlTimeProvider;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.util.Collection;

/**
 * // TODO_MA 注释： State Backend 定义了流式应用程序状态的存储方式和的检查点。
 * // TODO_MA 注释： 不同的状态后端以不同的方式存储其状态，并使用不同的数据结构来保存正在运行的应用程序的状态。
 * A <b>State Backend</b> defines how the state of a streaming application is stored and
 * checkpointed. Different State Backends store their state in different fashions, and use
 * different data structures to hold the state of a running application.
 *
 * // TODO_MA 注释： 例如，{@link org.apache.flink.runtime.state.memory.MemoryStateBackend 内存状态后端}
 * // TODO_MA 注释： 在 TaskManager 的内存中保持工作状态，并在 JobManager 的内存中存储检查点。
 * // TODO_MA 注释： 后端是轻量级的，没有附加的依赖关系，但不是高度可用的，并且仅支持小状态。
 * <p>For example, the {@link org.apache.flink.runtime.state.memory.MemoryStateBackend memory state backend}
 * keeps working state in the memory of the TaskManager and stores checkpoints in the memory of the
 * JobManager. The backend is lightweight and without additional dependencies, but not highly available
 * and supports only small state.
 *
 * // TODO_MA 注释： {@link org.apache.flink.runtime.state.filesystem.FsStateBackend 文件系统状态后端}
 * // TODO_MA 注释： 将工作状态保存在TaskManager的内存中，并将状态检查点存储在文件系统中
 * // TODO_MA 注释： 通常是复制的高可用性文件系统，比如 HDFS，Ceph，S3，GCS 等
 * <p>The {@link org.apache.flink.runtime.state.filesystem.FsStateBackend file system state backend}
 * keeps working state in the memory of the TaskManager and stores state checkpoints in a filesystem
 * (typically a replicated highly-available filesystem, like <a href="https://hadoop.apache.org/">HDFS</a>,
 * <a href="https://ceph.com/">Ceph</a>, <a href="https://aws.amazon.com/documentation/s3/">S3</a>,
 * <a href="https://cloud.google.com/storage/">GCS</a>, etc).
 *
 * // TODO_MA 注释： {@code RocksDBStateBackend} 将工作状态存储在 RocksDB 中，
 * // TODO_MA 注释： 并默认将状态检查点指向文件系统（类似于{@code FsStateBackend}）。
 * <p>The {@code RocksDBStateBackend} stores working state in <a href="http://rocksdb.org/">RocksDB</a>,
 * and checkpoints the state by default to a filesystem (similar to the {@code FsStateBackend}).
 *
 * <h2>Raw Bytes Storage and Backends</h2>
 *
 * // TODO_MA 注释： {@code StateBackend}为 raw bytes storage 以及 keyed state 和 operator state 创建服务。
 * The {@code StateBackend} creates services for <i>raw bytes storage</i> and for <i>keyed state</i> and <i>operator state</i>.
 *
 * // TODO_MA 注释： raw bytes storage（通过{@link CheckpointStreamFactory}）是基本的服务，它仅以容错方式存储字节。
 * // TODO_MA 注释： JobManager使用此服务存储检查点和恢复元数据，并且  keyed- and operator state backends
 * // TODO_MA 注释： 也通常使用此服务来存储检查点状态。
 * <p>The <i>raw bytes storage</i> (through the {@link CheckpointStreamFactory}) is the fundamental
 * service that simply stores bytes in a fault tolerant fashion.
 * This service is used by the JobManager to store checkpoint and recovery metadata and is typically
 * also used by the keyed- and operator state backends to store checkpointed state.
 *
 * // TODO_MA 注释： 此状态后端创建的{@link AbstractKeyedStateBackend}和
 * // TODO_MA 注释： {@link OperatorStateBackend}定义了如何保持键和运算符的工作状态。
 * // TODO_MA 注释： 他们还定义了如何经常使用原始字节存储（通过{@code CheckpointStreamFactory}）来检查该状态。
 * // TODO_MA 注释： 但是，也有可能例如键控状态后端仅实现到键/值存储的桥，并且不需要在检查点上将任何内容存储在原始字节存储中。
 * <p>The {@link AbstractKeyedStateBackend} and {@link OperatorStateBackend} created by this state
 * backend define how to hold the working state for keys and operators. They also define how to checkpoint
 * that state, frequently using the raw bytes storage (via the {@code CheckpointStreamFactory}).
 * However, it is also possible that for example a keyed state backend simply implements the bridge to
 * a key/value store, and that it does not need to store anything in the raw byte storage upon a checkpoint.
 *
 * <h2>Serializability</h2>
 *
 * State Backends need to be {@link java.io.Serializable serializable}, because they distributed
 * across parallel processes (for distributed execution) together with the streaming application code.
 *
 * <p>Because of that, {@code StateBackend} implementations (typically subclasses
 * of {@link AbstractStateBackend}) are meant to be like <i>factories</i> that create the proper
 * states stores that provide access to the persistent storage and hold the keyed- and operator
 * state data structures. That way, the State Backend can be very lightweight (contain only
 * configurations) which makes it easier to be serializable.
 *
 * <h2>Thread Safety</h2>
 *
 * // TODO_MA 注释： State backend 实现必须是线程安全的。
 * // TODO_MA 注释： 多个线程可能正在同时创建 streams and keyed-/operator state backends 状态后端。
 * State backend implementations have to be thread-safe. Multiple threads may be creating
 * streams and keyed-/operator state backends concurrently.
 *
 * // TODO_MA 注释： State Backend 决定了作业的状态及检查点是如何存储的。不同的状态存储后端会采用不同的方式来处理状态和检查点
 * // TODO_MA 注释： 主要是三个常见的实现类：
 * // TODO_MA 注释： 1、RocksDBStateBackend 会把状态存储在 RocksDB 中，将检查点存储在文件系统中（类似 FsStateBackend）
 * // TODO_MA 注释： 2、FsStateBackend 会将工作状态存储在 TaskManager 的内存中，将检查点存储在文件系统中（通常是分布式文件系统）
 * // TODO_MA 注释： 3、MemoryStateBackend 会将工作状态存储在 TaskManager 的内存中，将检查点存储在 JobManager 的内存中
 *
 *
 */
@PublicEvolving
public interface StateBackend extends java.io.Serializable {

	// ------------------------------------------------------------------------
	//  Checkpoint storage - the durable persistence of checkpoint data
	// ------------------------------------------------------------------------

	/**
	 * // TODO_MA 注释： 解析检查点的存储位置
	 * // TODO_MA 注释： 将指向检查点/保存点的给定指针解析到检查点位置。
	 * // TODO_MA 注释： 位置支持读取检查点元数据，或设置检查点存储位置。
	 * Resolves the given pointer to a checkpoint/savepoint into a checkpoint location. The location
	 * supports reading the checkpoint metadata, or disposing the checkpoint storage location.
	 *
	 * <p>If the state backend cannot understand the format of the pointer (for example because it
	 * was created by a different state backend) this method should throw an {@code IOException}.
	 *
	 * @param externalPointer The external checkpoint pointer to resolve.
	 * @return The checkpoint location handle.
	 * @throws IOException Thrown, if the state backend does not understand the pointer, or if
	 *                     the pointer could not be resolved due to an I/O error.
	 */
	CompletedCheckpointStorageLocation resolveCheckpoint(String externalPointer) throws IOException;

	/**
	 * // TODO_MA 注释： 创建检查点存储
	 * // TODO_MA 注释： 为给定作业的检查点创建存储。检查点存储用于写入检查点数据和元数据。
	 * Creates a storage for checkpoints for the given job. The checkpoint storage is
	 * used to write checkpoint data and metadata.
	 *
	 * @param jobId The job to store checkpoint data for.
	 * @return A checkpoint storage for the given job.
	 * @throws IOException Thrown if the checkpoint storage cannot be initialized.
	 */
	CheckpointStorage createCheckpointStorage(JobID jobId) throws IOException;

	// ------------------------------------------------------------------------
	//  Structure Backends 
	// ------------------------------------------------------------------------

	/**
	 * Creates a new {@link AbstractKeyedStateBackend} that is responsible for holding <b>keyed state</b> and checkpointing it.
	 * <p><i>Keyed State</i> is state where each value is bound to a key.
	 *
	 * @param env                  The environment of the task.
	 * @param jobID                The ID of the job that the task belongs to.
	 * @param operatorIdentifier   The identifier text of the operator.
	 * @param keySerializer        The key-serializer for the operator.
	 * @param numberOfKeyGroups    The number of key-groups aka max parallelism.
	 * @param keyGroupRange        Range of key-groups for which the to-be-created backend is responsible.
	 * @param kvStateRegistry      KvStateRegistry helper for this task.
	 * @param ttlTimeProvider      Provider for TTL logic to judge about state expiration.
	 * @param metricGroup          The parent metric group for all state backend metrics.
	 * @param stateHandles         The state handles for restore.
	 * @param cancelStreamRegistry The registry to which created closeable objects will be registered during restore.
	 * @param <K>                  The type of the keys by which the state is organized.
	 * @return The Keyed State Backend for the given job, operator, and key group range.
	 * @throws Exception This method may forward all exceptions that occur while instantiating the backend.
	 */
	<K> AbstractKeyedStateBackend<K> createKeyedStateBackend(Environment env, JobID jobID, String operatorIdentifier,
		TypeSerializer<K> keySerializer, int numberOfKeyGroups, KeyGroupRange keyGroupRange, TaskKvStateRegistry kvStateRegistry,
		TtlTimeProvider ttlTimeProvider, MetricGroup metricGroup, @Nonnull Collection<KeyedStateHandle> stateHandles,
		CloseableRegistry cancelStreamRegistry) throws Exception;

	/**
	 * // TODO_MA 注释： OperatorStateBackend，负责 operator state 的存储和检查点
	 * Creates a new {@link OperatorStateBackend} that can be used for storing operator state.
	 *
	 * // TODO_MA 注释： 运算符状态是与并行运算符（或函数）实例关联的状态，而不是与键关联的状态。
	 * <p>Operator state is state that is associated with parallel operator (or function) instances,
	 * rather than with keys.
	 *
	 * @param env                  The runtime environment of the executing task.
	 * @param operatorIdentifier   The identifier of the operator whose state should be stored.
	 * @param stateHandles         The state handles for restore.
	 * @param cancelStreamRegistry The registry to register streams to close if task canceled.
	 * @return The OperatorStateBackend for operator identified by the job and operator identifier.
	 * @throws Exception This method may forward all exceptions that occur while instantiating the backend.
	 */
	OperatorStateBackend createOperatorStateBackend(Environment env, String operatorIdentifier,
		@Nonnull Collection<OperatorStateHandle> stateHandles, CloseableRegistry cancelStreamRegistry) throws Exception;
}
