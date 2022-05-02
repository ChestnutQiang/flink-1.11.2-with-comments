/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.Function;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.runtime.OperatorIDPair;
import org.apache.flink.runtime.checkpoint.CheckpointRetentionPolicy;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.InputOutputFormatContainer;
import org.apache.flink.runtime.jobgraph.InputOutputFormatVertex;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobGraphUtils;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointingSettings;
import org.apache.flink.runtime.jobgraph.topology.DefaultLogicalPipelinedRegion;
import org.apache.flink.runtime.jobgraph.topology.DefaultLogicalTopology;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.operators.coordination.OperatorCoordinator;
import org.apache.flink.runtime.operators.util.TaskConfig;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.checkpoint.WithMasterCheckpointHook;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.InputSelectable;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.UdfStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.YieldingOperatorFactory;
import org.apache.flink.streaming.api.transformations.ShuffleMode;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RescalePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.tasks.StreamIterationHead;
import org.apache.flink.streaming.runtime.tasks.StreamIterationTail;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.SerializedValue;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration.MINIMAL_CHECKPOINT_TIME;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The StreamingJobGraphGenerator converts a {@link StreamGraph} into a {@link JobGraph}.
 */
@Internal
public class StreamingJobGraphGenerator {

	private static final Logger LOG = LoggerFactory.getLogger(StreamingJobGraphGenerator.class);

	private static final int MANAGED_MEMORY_FRACTION_SCALE = 16;

	private static final long DEFAULT_NETWORK_BUFFER_TIMEOUT = 100L;

	public static final long UNDEFINED_NETWORK_BUFFER_TIMEOUT = -1L;

	// ------------------------------------------------------------------------

	public static JobGraph createJobGraph(StreamGraph streamGraph) {
		return createJobGraph(streamGraph, null);
	}

	public static JobGraph createJobGraph(StreamGraph streamGraph, @Nullable JobID jobID) {

		/*************************************************
		 *
		 *  注释： 构建 JobGragh 返回
		 *  1、new StreamingJobGraphGenerator(streamGraph, jobID) 的时候，先构建一个 JobGragh 对象
		 *  2、createJobGraph() 然后填充属性值
		 */
		return new StreamingJobGraphGenerator(streamGraph, jobID).createJobGraph();
	}

	// ------------------------------------------------------------------------

	private final StreamGraph streamGraph;

	private final Map<Integer, JobVertex> jobVertices;
	private final JobGraph jobGraph;
	private final Collection<Integer> builtVertices;

	private final List<StreamEdge> physicalEdgesInOrder;

	private final Map<Integer, Map<Integer, StreamConfig>> chainedConfigs;

	private final Map<Integer, StreamConfig> vertexConfigs;
	private final Map<Integer, String> chainedNames;

	private final Map<Integer, ResourceSpec> chainedMinResources;
	private final Map<Integer, ResourceSpec> chainedPreferredResources;

	private final Map<Integer, InputOutputFormatContainer> chainedInputOutputFormats;

	private final StreamGraphHasher defaultStreamGraphHasher;
	private final List<StreamGraphHasher> legacyStreamGraphHashers;

	private StreamingJobGraphGenerator(StreamGraph streamGraph, @Nullable JobID jobID) {
		this.streamGraph = streamGraph;
		this.defaultStreamGraphHasher = new StreamGraphHasherV2();
		this.legacyStreamGraphHashers = Arrays.asList(new StreamGraphUserHashHasher());

		this.jobVertices = new HashMap<>();
		this.builtVertices = new HashSet<>();
		this.chainedConfigs = new HashMap<>();
		this.vertexConfigs = new HashMap<>();
		this.chainedNames = new HashMap<>();
		this.chainedMinResources = new HashMap<>();
		this.chainedPreferredResources = new HashMap<>();
		this.chainedInputOutputFormats = new HashMap<>();
		this.physicalEdgesInOrder = new ArrayList<>();

		/*************************************************
		 *
		 *  注释： 这地方是构建一个 JobGragh 对象
		 */
		jobGraph = new JobGraph(jobID, streamGraph.getJobName());
	}

	/*************************************************
	 *
	 *  注释： 这个方法，完成所谓的 StreamGraph 到 JobGraph 的转换
	 */
	private JobGraph createJobGraph() {
		preValidate();

		// TODO_MA 注释： 设置 ScheduleMode
		// make sure that all vertices start immediately
		jobGraph.setScheduleMode(streamGraph.getScheduleMode());

		// TODO_MA 注释： 为节点生成确定性哈希，以便在提交未发生变化的情况下对其进行标识。
		// Generate deterministic hashes for the nodes in order to identify them across submission iff they didn't change.
		Map<Integer, byte[]> hashes = defaultStreamGraphHasher.traverseStreamGraphAndGenerateHashes(streamGraph);

		// TODO_MA 注释： 生成旧版本哈希以向后兼容
		// Generate legacy version hashes for backwards compatibility
		List<Map<Integer, byte[]>> legacyHashes = new ArrayList<>(legacyStreamGraphHashers.size());
		for(StreamGraphHasher hasher : legacyStreamGraphHashers) {
			legacyHashes.add(hasher.traverseStreamGraphAndGenerateHashes(streamGraph));
		}

		/*************************************************
		 *
		 *  注释： 重点： 设置 Chaining, 将可以 Chain 到一起的 StreamNode Chain 在一起，
		 *  这里会生成相应的 JobVertex 、JobEdge 、 IntermediateDataSet 对象
		 *  把能 chain 在一起的 Operator 都合并了，变成了 OperatorChain
		 *  -
		 *  大致逻辑：
		 *  这里的逻辑大致可以理解为，挨个遍历节点：
		 *  1、如果该节点是一个 chain 的头节点，就生成一个 JobVertex，
		 *  2、如果不是头节点，就要把自身配置并入头节点，然后把头节点和自己的出边相连；
		 *  对于不能chain的节点，当作只有头节点处理即可
		 *  -
		 *  作用：
		 *  能减少线程之间的切换，减少消息的序列化/反序列化，减少数据在缓冲区的交换，减少了延迟的同时提高整体的吞吐量。
		 */
		setChaining(hashes, legacyHashes);

		// TODO_MA 注释： 设置 PhysicalEdges
		// TODO_MA 注释： 将每个 JobVertex 的入边集合也序列化到该 JobVertex 的 StreamConfig 中
		// TODO_MA 注释： 出边集合，已经在 上面的代码中，已经搞定了。
		setPhysicalEdges();

		// TODO_MA 注释： 设置 SlotSharingAndCoLocation
		setSlotSharingAndCoLocation();

		// TODO_MA 注释： 设置 ManagedMemoryFraction
		setManagedMemoryFraction(
			Collections.unmodifiableMap(jobVertices),
			Collections.unmodifiableMap(vertexConfigs),
			Collections.unmodifiableMap(chainedConfigs),
			id -> streamGraph.getStreamNode(id).getMinResources(),
			id -> streamGraph.getStreamNode(id).getManagedMemoryWeight()
		);

		// TODO_MA 注释： 设置 SnapshotSettings， checkpoint 相关的设置
		configureCheckpointing();

		// TODO_MA 注释： 设置 SavepointRestoreSettings
		jobGraph.setSavepointRestoreSettings(streamGraph.getSavepointRestoreSettings());

		// TODO_MA 注释： 添加 UserArtifactEntries
		JobGraphUtils.addUserArtifactEntries(streamGraph.getUserArtifacts(), jobGraph);

		// set the ExecutionConfig last when it has been finalized
		try {

			// TODO_MA 注释： 传递执行环境配置， 设置 ExecutionConfig
			jobGraph.setExecutionConfig(streamGraph.getExecutionConfig());

		} catch(IOException e) {
			throw new IllegalConfigurationException(
				"Could not serialize the ExecutionConfig." + "This indicates that non-serializable types (like custom serializers) were registered");
		}

		// TODO_MA 注释： 返回 JobGragh
		return jobGraph;
	}

	@SuppressWarnings("deprecation")
	private void preValidate() {
		CheckpointConfig checkpointConfig = streamGraph.getCheckpointConfig();

		if(checkpointConfig.isCheckpointingEnabled()) {
			// temporarily forbid checkpointing for iterative jobs
			if(streamGraph.isIterative() && !checkpointConfig.isForceCheckpointing()) {
				throw new UnsupportedOperationException(
					"Checkpointing is currently not supported by default for iterative jobs, as we cannot guarantee exactly once semantics. " + "State checkpoints happen normally, but records in-transit during the snapshot will be lost upon failure. " + "\nThe user can force enable state checkpoints with the reduced guarantees by calling: env.enableCheckpointing(interval,true)");
			}

			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			for(StreamNode node : streamGraph.getStreamNodes()) {
				StreamOperatorFactory operatorFactory = node.getOperatorFactory();
				if(operatorFactory != null) {
					Class<?> operatorClass = operatorFactory.getStreamOperatorClass(classLoader);
					if(InputSelectable.class.isAssignableFrom(operatorClass)) {
						throw new UnsupportedOperationException(
							"Checkpointing is currently not supported for operators that implement InputSelectable:" + operatorClass.getName());
					}
				}
			}
		}

		if(checkpointConfig.isUnalignedCheckpointsEnabled() && getCheckpointingMode(checkpointConfig) != CheckpointingMode.EXACTLY_ONCE) {
			LOG.warn("Unaligned checkpoints can only be used with checkpointing mode EXACTLY_ONCE");
			checkpointConfig.enableUnalignedCheckpoints(false);
		}
	}

	private void setPhysicalEdges() {
		Map<Integer, List<StreamEdge>> physicalInEdgesInOrder = new HashMap<Integer, List<StreamEdge>>();

		for(StreamEdge edge : physicalEdgesInOrder) {
			int target = edge.getTargetId();
			List<StreamEdge> inEdges = physicalInEdgesInOrder.computeIfAbsent(target, k -> new ArrayList<>());
			inEdges.add(edge);
		}

		for(Map.Entry<Integer, List<StreamEdge>> inEdges : physicalInEdgesInOrder.entrySet()) {
			int vertex = inEdges.getKey();
			List<StreamEdge> edgeList = inEdges.getValue();

			vertexConfigs.get(vertex).setInPhysicalEdges(edgeList);
		}
	}

	/**
	 * Sets up task chains from the source {@link StreamNode} instances.
	 *
	 * <p>This will recursively create all {@link JobVertex} instances.
	 */
	private void setChaining(Map<Integer, byte[]> hashes, List<Map<Integer, byte[]>> legacyHashes) {

		/*************************************************
		 *
		 *  注释：
		 *  1、一个 StreamNode　也可以认为是　做了 chain 动作  StreamNode -> JobVertex
		 *  2、两个 StreamNode　做了 chain 动作  StreamNode + StreamNode -> JobVertex
		 */
		// TODO_MA 注释： 处理每个 StreamNode
		for(Integer sourceNodeId : streamGraph.getSourceIDs()) {

			/*************************************************
			 *
			 *  注释： 把能 chain 在一起的 StreamNode 都 chain 在一起
			 */
			createChain(sourceNodeId, 0, new OperatorChainInfo(sourceNodeId, hashes, legacyHashes, streamGraph));
		}
	}

	/*************************************************
	 *
	 *  注释： 是个递归方法，当一个 SteamEdge chain 在一起了之后，也有可能继续与前一个 Operator 或者 后一个 Operator chain 在一起
	 */
	private List<StreamEdge> createChain(Integer currentNodeId, int chainIndex, OperatorChainInfo chainInfo) {
		Integer startNodeId = chainInfo.getStartNodeId();
		if(!builtVertices.contains(startNodeId)) {

			List<StreamEdge> transitiveOutEdges = new ArrayList<StreamEdge>();

			// TODO_MA 注释： 存储可 chain 的 StreamEdge
			// TODO_MA 注释： 一个 StreamEdge 链接了上下游两个 StreamNode
			List<StreamEdge> chainableOutputs = new ArrayList<StreamEdge>();

			// TODO_MA 注释： 存储不可 chain 的 StreamEdge
			List<StreamEdge> nonChainableOutputs = new ArrayList<StreamEdge>();

			// TODO_MA 注释： 当前要处理的 StreamNode
			StreamNode currentNode = streamGraph.getStreamNode(currentNodeId);

			/*************************************************
			 *
			 *  注释： 判断是否可以 chain 在一起！
			 *  当前这个地方做的事情，只是当前这个 StreamNode 和它的直接下游 StreamNode
			 */
			for(StreamEdge outEdge : currentNode.getOutEdges()) {

				/*************************************************
				 *
				 *  注释： 判断一个 StreamGraph 中的一个 StreamEdge 链接的上下游 Operator（StreamNode） 是否可以 chain 在一起
				 */
				if(isChainable(outEdge, streamGraph)) {

					// TODO_MA 注释： 加入可 chain 集合
					chainableOutputs.add(outEdge);
				} else {

					// TODO_MA 注释： 加入不可 chain 集合
					nonChainableOutputs.add(outEdge);
				}
			}

			// TODO_MA 注释： 把可以 chain 在一起的 StreamEdge 两边的 Operator chain 在一个形成一个 OperatorChain
			for(StreamEdge chainable : chainableOutputs) {

				// TODO_MA 注释： 递归 chain
				// TODO_MA 注释： 如果可以 chain 在一起的话，这里的 chainIndex 会加 1
				transitiveOutEdges.addAll(createChain(chainable.getTargetId(), chainIndex + 1, chainInfo));
			}

			for(StreamEdge nonChainable : nonChainableOutputs) {
				transitiveOutEdges.add(nonChainable);
				// TODO_MA 注释： 不能 chain 一起的话，这里的 chainIndex 是从 0 开始算的，后面也肯定会走到 createJobVertex 的逻辑
				createChain(nonChainable.getTargetId(), 0, chainInfo.newChain(nonChainable.getTargetId()));
			}

			chainedNames.put(currentNodeId, createChainedName(currentNodeId, chainableOutputs));
			chainedMinResources.put(currentNodeId, createChainedMinResources(currentNodeId, chainableOutputs));
			chainedPreferredResources.put(currentNodeId, createChainedPreferredResources(currentNodeId, chainableOutputs));

			OperatorID currentOperatorId = chainInfo.addNodeToChain(currentNodeId, chainedNames.get(currentNodeId));

			if(currentNode.getInputFormat() != null) {
				getOrCreateFormatContainer(startNodeId).addInputFormat(currentOperatorId, currentNode.getInputFormat());
			}
			if(currentNode.getOutputFormat() != null) {
				getOrCreateFormatContainer(startNodeId).addOutputFormat(currentOperatorId, currentNode.getOutputFormat());
			}

			/*************************************************
			 *
			 *  注释： 把chain在一起的多个 Operator 创建成一个 JobVertex
			 *  如果当前节点是 chain 的起始节点, 则直接创建 JobVertex 并返回 StreamConfig, 否则先创建一个空的 StreamConfig
			 *  这里实际上，如果节点不能 chain 在一起，那么 currentNodeId 跟 startNodeId 肯定是不相等的
			 *  createJobVertex 函数就是根据 StreamNode 创建对应的 JobVertex, 并返回了空的 StreamConfig
			 */
			StreamConfig config = currentNodeId.equals(startNodeId) ?
				createJobVertex(startNodeId, chainInfo) : new StreamConfig(new Configuration());

			// TODO_MA 注释： 设置 JobVertex 的 StreamConfig, 基本上是将 StreamNode 中的配置设置到 StreamConfig 中
			setVertexConfig(currentNodeId, config, chainableOutputs, nonChainableOutputs);

			if(currentNodeId.equals(startNodeId)) {

				// TODO_MA 注释： chain 开始
				config.setChainStart();
				config.setChainIndex(0);
				config.setOperatorName(streamGraph.getStreamNode(currentNodeId).getOperatorName());


				// TODO_MA 注释： chain 在一起的多条边 connect 在一起
				for(StreamEdge edge : transitiveOutEdges) {
					connect(startNodeId, edge);
				}

				config.setOutEdgesInOrder(transitiveOutEdges);
				config.setTransitiveChainedTaskConfigs(chainedConfigs.get(startNodeId));

			} else {
				chainedConfigs.computeIfAbsent(startNodeId, k -> new HashMap<Integer, StreamConfig>());

				config.setChainIndex(chainIndex);
				StreamNode node = streamGraph.getStreamNode(currentNodeId);
				config.setOperatorName(node.getOperatorName());
				chainedConfigs.get(startNodeId).put(currentNodeId, config);
			}

			config.setOperatorID(currentOperatorId);


			// TODO_MA 注释：  chain 结束
			if(chainableOutputs.isEmpty()) {
				config.setChainEnd();
			}
			return transitiveOutEdges;

		} else {
			return new ArrayList<>();
		}
	}

	private InputOutputFormatContainer getOrCreateFormatContainer(Integer startNodeId) {
		return chainedInputOutputFormats
			.computeIfAbsent(startNodeId, k -> new InputOutputFormatContainer(Thread.currentThread().getContextClassLoader()));
	}

	private String createChainedName(Integer vertexID, List<StreamEdge> chainedOutputs) {
		String operatorName = streamGraph.getStreamNode(vertexID).getOperatorName();
		if(chainedOutputs.size() > 1) {
			List<String> outputChainedNames = new ArrayList<>();
			for(StreamEdge chainable : chainedOutputs) {
				outputChainedNames.add(chainedNames.get(chainable.getTargetId()));
			}
			return operatorName + " -> (" + StringUtils.join(outputChainedNames, ", ") + ")";
		} else if(chainedOutputs.size() == 1) {
			return operatorName + " -> " + chainedNames.get(chainedOutputs.get(0).getTargetId());
		} else {
			return operatorName;
		}
	}

	private ResourceSpec createChainedMinResources(Integer vertexID, List<StreamEdge> chainedOutputs) {
		ResourceSpec minResources = streamGraph.getStreamNode(vertexID).getMinResources();
		for(StreamEdge chainable : chainedOutputs) {
			minResources = minResources.merge(chainedMinResources.get(chainable.getTargetId()));
		}
		return minResources;
	}

	private ResourceSpec createChainedPreferredResources(Integer vertexID, List<StreamEdge> chainedOutputs) {
		ResourceSpec preferredResources = streamGraph.getStreamNode(vertexID).getPreferredResources();
		for(StreamEdge chainable : chainedOutputs) {
			preferredResources = preferredResources.merge(chainedPreferredResources.get(chainable.getTargetId()));
		}
		return preferredResources;
	}

	private StreamConfig createJobVertex(Integer streamNodeId, OperatorChainInfo chainInfo) {

		JobVertex jobVertex;

		// TODO_MA 注释： 获取 startStreamNode
		StreamNode streamNode = streamGraph.getStreamNode(streamNodeId);

		byte[] hash = chainInfo.getHash(streamNodeId);

		if(hash == null) {
			throw new IllegalStateException("Cannot find node hash. " + "Did you generate them before calling this method?");
		}

		// TODO_MA 注释： 生成一个 JobVertexID
		JobVertexID jobVertexId = new JobVertexID(hash);

		List<Tuple2<byte[], byte[]>> chainedOperators = chainInfo.getChainedOperatorHashes(streamNodeId);
		List<OperatorIDPair> operatorIDPairs = new ArrayList<>();
		if(chainedOperators != null) {
			for(Tuple2<byte[], byte[]> chainedOperator : chainedOperators) {
				OperatorID userDefinedOperatorID = chainedOperator.f1 == null ? null : new OperatorID(chainedOperator.f1);
				operatorIDPairs.add(OperatorIDPair.of(new OperatorID(chainedOperator.f0), userDefinedOperatorID));
			}
		}

		/*************************************************
		 *
		 *  注释： JobVertex 初始化
		 */
		if(chainedInputOutputFormats.containsKey(streamNodeId)) {
			jobVertex = new InputOutputFormatVertex(chainedNames.get(streamNodeId), jobVertexId, operatorIDPairs);
			chainedInputOutputFormats.get(streamNodeId).write(new TaskConfig(jobVertex.getConfiguration()));
		} else {

			// TODO_MA 注释： 创建一个 JobVertex
			jobVertex = new JobVertex(chainedNames.get(streamNodeId), jobVertexId, operatorIDPairs);
		}

		for(OperatorCoordinator.Provider coordinatorProvider : chainInfo.getCoordinatorProviders()) {
			try {
				/*************************************************
				 *
				 *  注释： 添加 OperatorCoordinator
				 */
				jobVertex.addOperatorCoordinator(new SerializedValue<>(coordinatorProvider));
			} catch(IOException e) {
				throw new FlinkRuntimeException(
					String.format("Coordinator Provider for node %s is not serializable.", chainedNames.get(streamNodeId)));
			}
		}

		jobVertex.setResources(chainedMinResources.get(streamNodeId), chainedPreferredResources.get(streamNodeId));

		jobVertex.setInvokableClass(streamNode.getJobVertexClass());

		/*************************************************
		 *
		 *  注释： 获取 JobVertex 的并行度
		 */
		int parallelism = streamNode.getParallelism();
		if(parallelism > 0) {
			jobVertex.setParallelism(parallelism);
		} else {
			parallelism = jobVertex.getParallelism();
		}

		// TODO_MA 注释： 设置最大并行度
		jobVertex.setMaxParallelism(streamNode.getMaxParallelism());

		if(LOG.isDebugEnabled()) {
			LOG.debug("Parallelism set: {} for {}", parallelism, streamNodeId);
		}

		// TODO: inherit InputDependencyConstraint from the head operator
		jobVertex.setInputDependencyConstraint(streamGraph.getExecutionConfig().getDefaultInputDependencyConstraint());

		jobVertices.put(streamNodeId, jobVertex);
		builtVertices.add(streamNodeId);

		/*************************************************
		 *
		 *  注释： 将生成好的 JobVertex 加入到： JobGraph
		 */
		jobGraph.addVertex(jobVertex);

		return new StreamConfig(jobVertex.getConfiguration());
	}

	@SuppressWarnings("unchecked")
	private void setVertexConfig(Integer vertexID, StreamConfig config, List<StreamEdge> chainableOutputs, List<StreamEdge> nonChainableOutputs) {

		StreamNode vertex = streamGraph.getStreamNode(vertexID);

		config.setVertexID(vertexID);

		config.setTypeSerializersIn(vertex.getTypeSerializersIn());
		config.setTypeSerializerOut(vertex.getTypeSerializerOut());

		// iterate edges, find sideOutput edges create and save serializers for each outputTag type
		for(StreamEdge edge : chainableOutputs) {
			if(edge.getOutputTag() != null) {
				config.setTypeSerializerSideOut(edge.getOutputTag(),
					edge.getOutputTag().getTypeInfo().createSerializer(streamGraph.getExecutionConfig()));
			}
		}
		for(StreamEdge edge : nonChainableOutputs) {
			if(edge.getOutputTag() != null) {
				config.setTypeSerializerSideOut(edge.getOutputTag(),
					edge.getOutputTag().getTypeInfo().createSerializer(streamGraph.getExecutionConfig()));
			}
		}

		config.setStreamOperatorFactory(vertex.getOperatorFactory());
		config.setOutputSelectors(vertex.getOutputSelectors());

		config.setNumberOfOutputs(nonChainableOutputs.size());
		config.setNonChainedOutputs(nonChainableOutputs);
		config.setChainedOutputs(chainableOutputs);

		config.setTimeCharacteristic(streamGraph.getTimeCharacteristic());

		final CheckpointConfig checkpointCfg = streamGraph.getCheckpointConfig();

		config.setStateBackend(streamGraph.getStateBackend());
		config.setCheckpointingEnabled(checkpointCfg.isCheckpointingEnabled());
		config.setCheckpointMode(getCheckpointingMode(checkpointCfg));
		config.setUnalignedCheckpointsEnabled(checkpointCfg.isUnalignedCheckpointsEnabled());

		for(int i = 0; i < vertex.getStatePartitioners().length; i++) {
			config.setStatePartitioner(i, vertex.getStatePartitioners()[i]);
		}
		config.setStateKeySerializer(vertex.getStateKeySerializer());

		Class<? extends AbstractInvokable> vertexClass = vertex.getJobVertexClass();

		if(vertexClass.equals(StreamIterationHead.class) || vertexClass.equals(StreamIterationTail.class)) {
			config.setIterationId(streamGraph.getBrokerID(vertexID));
			config.setIterationWaitTime(streamGraph.getLoopTimeout(vertexID));
		}

		vertexConfigs.put(vertexID, config);
	}

	private CheckpointingMode getCheckpointingMode(CheckpointConfig checkpointConfig) {
		CheckpointingMode checkpointingMode = checkpointConfig.getCheckpointingMode();

		checkArgument(checkpointingMode == CheckpointingMode.EXACTLY_ONCE || checkpointingMode == CheckpointingMode.AT_LEAST_ONCE,
			"Unexpected checkpointing mode.");

		if(checkpointConfig.isCheckpointingEnabled()) {
			return checkpointingMode;
		} else {
			// the "at-least-once" input handler is slightly cheaper (in the absence of checkpoints),
			// so we use that one if checkpointing is not enabled
			return CheckpointingMode.AT_LEAST_ONCE;
		}
	}

	private void connect(Integer headOfChain, StreamEdge edge) {

		physicalEdgesInOrder.add(edge);

		Integer downStreamVertexID = edge.getTargetId();

		JobVertex headVertex = jobVertices.get(headOfChain);
		JobVertex downStreamVertex = jobVertices.get(downStreamVertexID);

		StreamConfig downStreamConfig = new StreamConfig(downStreamVertex.getConfiguration());

		downStreamConfig.setNumberOfInputs(downStreamConfig.getNumberOfInputs() + 1);

		StreamPartitioner<?> partitioner = edge.getPartitioner();

		ResultPartitionType resultPartitionType;
		switch(edge.getShuffleMode()) {
			case PIPELINED:
				resultPartitionType = ResultPartitionType.PIPELINED_BOUNDED;
				break;
			case BATCH:
				resultPartitionType = ResultPartitionType.BLOCKING;
				break;
			case UNDEFINED:
				resultPartitionType = determineResultPartitionType(partitioner);
				break;
			default:
				throw new UnsupportedOperationException("Data exchange mode " + edge.getShuffleMode() + " is not supported yet.");
		}

		checkAndResetBufferTimeout(resultPartitionType, edge);

		JobEdge jobEdge;
		if(isPointwisePartitioner(partitioner)) {
			jobEdge = downStreamVertex.connectNewDataSetAsInput(headVertex, DistributionPattern.POINTWISE, resultPartitionType);
		} else {
			jobEdge = downStreamVertex.connectNewDataSetAsInput(headVertex, DistributionPattern.ALL_TO_ALL, resultPartitionType);
		}
		// set strategy name so that web interface can show it.
		jobEdge.setShipStrategyName(partitioner.toString());

		if(LOG.isDebugEnabled()) {
			LOG.debug("CONNECTED: {} - {} -> {}", partitioner.getClass().getSimpleName(), headOfChain, downStreamVertexID);
		}
	}

	private void checkAndResetBufferTimeout(ResultPartitionType type, StreamEdge edge) {
		long bufferTimeout = edge.getBufferTimeout();
		if(type.isBlocking() && bufferTimeout != UNDEFINED_NETWORK_BUFFER_TIMEOUT) {
			throw new UnsupportedOperationException(
				"Blocking partition does not support buffer timeout " + bufferTimeout + " for src operator in edge " + edge
					.toString() + ". \nPlease either reset buffer timeout as -1 or use the non-blocking partition.");
		}

		if(type.isPipelined() && bufferTimeout == UNDEFINED_NETWORK_BUFFER_TIMEOUT) {
			edge.setBufferTimeout(DEFAULT_NETWORK_BUFFER_TIMEOUT);
		}
	}

	private static boolean isPointwisePartitioner(StreamPartitioner<?> partitioner) {
		return partitioner instanceof ForwardPartitioner || partitioner instanceof RescalePartitioner;
	}

	private ResultPartitionType determineResultPartitionType(StreamPartitioner<?> partitioner) {
		switch(streamGraph.getGlobalDataExchangeMode()) {
			case ALL_EDGES_BLOCKING:
				return ResultPartitionType.BLOCKING;
			case FORWARD_EDGES_PIPELINED:
				if(partitioner instanceof ForwardPartitioner) {
					return ResultPartitionType.PIPELINED_BOUNDED;
				} else {
					return ResultPartitionType.BLOCKING;
				}
			case POINTWISE_EDGES_PIPELINED:
				if(isPointwisePartitioner(partitioner)) {
					return ResultPartitionType.PIPELINED_BOUNDED;
				} else {
					return ResultPartitionType.BLOCKING;
				}
			case ALL_EDGES_PIPELINED:
				return ResultPartitionType.PIPELINED_BOUNDED;
			default:
				throw new RuntimeException("Unrecognized global data exchange mode " + streamGraph.getGlobalDataExchangeMode());
		}
	}

	/*************************************************
	 *
	 *  注释： 用于判断 两个 JobVertex 是否可以 chain 在一起
	 *  条件： 9个条件
	 *  // 1、下游节点的入度为1 （也就是说下游节点没有来自其他节点的输入）
	 *  downStreamVertex.getInEdges().size() == 1;
	 *  -
	 *  // 2、上下游节点都在同一个 slot group 中
	 *  upStreamVertex.isSameSlotSharingGroup(downStreamVertex);
	 *  -
	 *  // 3、前后算子不为空
	 *  !(downStreamOperator == null || upStreamOperator == null);
	 *  -
	 *  // 4、上游节点的 chain 策略为 ALWAYS 或 HEAD（只能与下游链接，不能与上游链接，Source 默认是 HEAD）
	 *  !upStreamOperator.getChainingStrategy() == ChainingStrategy.NEVER;
	 *  -
	 *  // 5、下游节点的 chain 策略为 ALWAYS（可以与上下游链接，map、flatmap、filter 等默认是 ALWAYS）
	 *  !downStreamOperator.getChainingStrategy() != ChainingStrategy.ALWAYS;
	 *  -
	 *  // 6、两个节点间物理分区逻辑是 ForwardPartitioner
	 *  (edge.getPartitioner() instanceof ForwardPartitioner);
	 *  -
	 *  // 7、两个算子间的shuffle方式不等于批处理模式
	 *  edge.getShuffleMode() != ShuffleMode.BATCH;
	 *  -
	 *  // 8、上下游的并行度一致
	 *  upStreamVertex.getParallelism() == downStreamVertex.getParallelism();
	 *  -
	 *  // 9、用户没有禁用 chain
	 *  streamGraph.isChainingEnabled();
	 */
	public static boolean isChainable(StreamEdge edge, StreamGraph streamGraph) {

		// TODO_MA 注释： 获取上游 SourceVertex
		StreamNode upStreamVertex = streamGraph.getSourceVertex(edge);

		// TODO_MA 注释： 获取下游 TargetVertex
		StreamNode downStreamVertex = streamGraph.getTargetVertex(edge);

		/*************************************************
		 *
		 *  注释： 判断是否能 chain 在一起
		 */
		return downStreamVertex.getInEdges().size() == 1

			// TODO_MA 注释： 上下游算子实例处于同一个SlotSharingGroup中
			&& upStreamVertex.isSameSlotSharingGroup(downStreamVertex)

			// TODO_MA 注释： 这里面有 3 个条件
			&& areOperatorsChainable(upStreamVertex, downStreamVertex, streamGraph)

			// TODO_MA 注释： 两个算子间的物理分区逻辑是ForwardPartitioner
			&& (edge.getPartitioner() instanceof ForwardPartitioner)

			// TODO_MA 注释： 两个算子间的shuffle方式不等于批处理模式
			&& edge.getShuffleMode() != ShuffleMode.BATCH

			// TODO_MA 注释： 上下游算子实例的并行度相同
			&& upStreamVertex.getParallelism() == downStreamVertex.getParallelism()

			// TODO_MA 注释： 启动了 chain
			&& streamGraph.isChainingEnabled();
	}

	@VisibleForTesting
	static boolean areOperatorsChainable(StreamNode upStreamVertex, StreamNode downStreamVertex, StreamGraph streamGraph) {

		// TODO_MA 注释： 获取 上游 Operator
		StreamOperatorFactory<?> upStreamOperator = upStreamVertex.getOperatorFactory();

		// TODO_MA 注释： 获取 下游 Operator
		StreamOperatorFactory<?> downStreamOperator = downStreamVertex.getOperatorFactory();

		// TODO_MA 注释： 如果上下游有一个为空，则不能进行 chain
		if(downStreamOperator == null || upStreamOperator == null) {
			return false;
		}

		/*************************************************
		 *
		 *  注释：
		 *  1、上游算子的链接策略是 always 或者 head
		 *  2、下游算子的链接策略必须是 always
		 */
		if(upStreamOperator.getChainingStrategy() == ChainingStrategy.NEVER ||
			downStreamOperator.getChainingStrategy() != ChainingStrategy.ALWAYS) {
			return false;
		}

		// yielding operators cannot be chained to legacy sources
		if(downStreamOperator instanceof YieldingOperatorFactory) {
			// unfortunately the information that vertices have been chained is not preserved at this point
			return !getHeadOperator(upStreamVertex, streamGraph).isStreamSource();
		}
		return true;
	}

	/**
	 * Backtraces the head of an operator chain.
	 */
	private static StreamOperatorFactory<?> getHeadOperator(StreamNode upStreamVertex, StreamGraph streamGraph) {
		if(upStreamVertex.getInEdges().size() == 1 && isChainable(upStreamVertex.getInEdges().get(0), streamGraph)) {
			return getHeadOperator(streamGraph.getSourceVertex(upStreamVertex.getInEdges().get(0)), streamGraph);
		}
		return upStreamVertex.getOperatorFactory();
	}

	private void setSlotSharingAndCoLocation() {

		// TODO_MA 注释：
		setSlotSharing();

		// TODO_MA 注释：
		setCoLocation();
	}

	private void setSlotSharing() {
		final Map<String, SlotSharingGroup> specifiedSlotSharingGroups = new HashMap<>();
		final Map<JobVertexID, SlotSharingGroup> vertexRegionSlotSharingGroups = buildVertexRegionSlotSharingGroups();

		for(Entry<Integer, JobVertex> entry : jobVertices.entrySet()) {

			final JobVertex vertex = entry.getValue();
			final String slotSharingGroupKey = streamGraph.getStreamNode(entry.getKey()).getSlotSharingGroup();

			final SlotSharingGroup effectiveSlotSharingGroup;
			if(slotSharingGroupKey == null) {
				effectiveSlotSharingGroup = null;
			} else if(slotSharingGroupKey.equals(StreamGraphGenerator.DEFAULT_SLOT_SHARING_GROUP)) {
				// fallback to the region slot sharing group by default
				effectiveSlotSharingGroup = vertexRegionSlotSharingGroups.get(vertex.getID());
			} else {
				effectiveSlotSharingGroup = specifiedSlotSharingGroups.computeIfAbsent(slotSharingGroupKey, k -> new SlotSharingGroup());
			}

			vertex.setSlotSharingGroup(effectiveSlotSharingGroup);
		}
	}

	/**
	 * Maps a vertex to its region slot sharing group.
	 * If {@link StreamGraph#isAllVerticesInSameSlotSharingGroupByDefault()}
	 * returns true, all regions will be in the same slot sharing group.
	 */
	private Map<JobVertexID, SlotSharingGroup> buildVertexRegionSlotSharingGroups() {
		final Map<JobVertexID, SlotSharingGroup> vertexRegionSlotSharingGroups = new HashMap<>();
		final SlotSharingGroup defaultSlotSharingGroup = new SlotSharingGroup();

		final boolean allRegionsInSameSlotSharingGroup = streamGraph.isAllVerticesInSameSlotSharingGroupByDefault();

		final Set<DefaultLogicalPipelinedRegion> regions = new DefaultLogicalTopology(jobGraph).getLogicalPipelinedRegions();
		for(DefaultLogicalPipelinedRegion region : regions) {
			final SlotSharingGroup regionSlotSharingGroup;
			if(allRegionsInSameSlotSharingGroup) {
				regionSlotSharingGroup = defaultSlotSharingGroup;
			} else {
				regionSlotSharingGroup = new SlotSharingGroup();
			}

			for(JobVertexID jobVertexID : region.getVertexIDs()) {
				vertexRegionSlotSharingGroups.put(jobVertexID, regionSlotSharingGroup);
			}
		}

		return vertexRegionSlotSharingGroups;
	}

	private void setCoLocation() {
		final Map<String, Tuple2<SlotSharingGroup, CoLocationGroup>> coLocationGroups = new HashMap<>();

		for(Entry<Integer, JobVertex> entry : jobVertices.entrySet()) {

			final StreamNode node = streamGraph.getStreamNode(entry.getKey());
			final JobVertex vertex = entry.getValue();
			final SlotSharingGroup sharingGroup = vertex.getSlotSharingGroup();

			// configure co-location constraint
			final String coLocationGroupKey = node.getCoLocationGroup();
			if(coLocationGroupKey != null) {
				if(sharingGroup == null) {
					throw new IllegalStateException("Cannot use a co-location constraint without a slot sharing group");
				}

				Tuple2<SlotSharingGroup, CoLocationGroup> constraint = coLocationGroups
					.computeIfAbsent(coLocationGroupKey, k -> new Tuple2<>(sharingGroup, new CoLocationGroup()));

				if(constraint.f0 != sharingGroup) {
					throw new IllegalStateException("Cannot co-locate operators from different slot sharing groups");
				}

				vertex.updateCoLocationGroup(constraint.f1);
				constraint.f1.addVertex(vertex);
			}
		}
	}

	private static void setManagedMemoryFraction(final Map<Integer, JobVertex> jobVertices, final Map<Integer, StreamConfig> operatorConfigs,
		final Map<Integer, Map<Integer, StreamConfig>> vertexChainedConfigs,
		final java.util.function.Function<Integer, ResourceSpec> operatorResourceRetriever,
		final java.util.function.Function<Integer, Integer> operatorManagedMemoryWeightRetriever) {

		// all slot sharing groups in this job
		final Set<SlotSharingGroup> slotSharingGroups = Collections.newSetFromMap(new IdentityHashMap<>());

		// maps a job vertex ID to its head operator ID
		final Map<JobVertexID, Integer> vertexHeadOperators = new HashMap<>();

		// maps a job vertex ID to IDs of all operators in the vertex
		final Map<JobVertexID, Set<Integer>> vertexOperators = new HashMap<>();

		for(Entry<Integer, JobVertex> entry : jobVertices.entrySet()) {
			final int headOperatorId = entry.getKey();
			final JobVertex jobVertex = entry.getValue();

			final SlotSharingGroup jobVertexSlotSharingGroup = jobVertex.getSlotSharingGroup();

			checkState(jobVertexSlotSharingGroup != null, "JobVertex slot sharing group must not be null");
			slotSharingGroups.add(jobVertexSlotSharingGroup);

			vertexHeadOperators.put(jobVertex.getID(), headOperatorId);

			final Set<Integer> operatorIds = new HashSet<>();
			operatorIds.add(headOperatorId);
			operatorIds.addAll(vertexChainedConfigs.getOrDefault(headOperatorId, Collections.emptyMap()).keySet());
			vertexOperators.put(jobVertex.getID(), operatorIds);
		}

		for(SlotSharingGroup slotSharingGroup : slotSharingGroups) {
			setManagedMemoryFractionForSlotSharingGroup(slotSharingGroup, vertexHeadOperators, vertexOperators, operatorConfigs, vertexChainedConfigs,
				operatorResourceRetriever, operatorManagedMemoryWeightRetriever);
		}
	}

	private static void setManagedMemoryFractionForSlotSharingGroup(final SlotSharingGroup slotSharingGroup,
		final Map<JobVertexID, Integer> vertexHeadOperators, final Map<JobVertexID, Set<Integer>> vertexOperators,
		final Map<Integer, StreamConfig> operatorConfigs, final Map<Integer, Map<Integer, StreamConfig>> vertexChainedConfigs,
		final java.util.function.Function<Integer, ResourceSpec> operatorResourceRetriever,
		final java.util.function.Function<Integer, Integer> operatorManagedMemoryWeightRetriever) {

		final int groupManagedMemoryWeight = slotSharingGroup.getJobVertexIds().stream().flatMap(vid -> vertexOperators.get(vid).stream())
			.mapToInt(operatorManagedMemoryWeightRetriever::apply).sum();

		for(JobVertexID jobVertexID : slotSharingGroup.getJobVertexIds()) {
			for(int operatorNodeId : vertexOperators.get(jobVertexID)) {
				final StreamConfig operatorConfig = operatorConfigs.get(operatorNodeId);
				final ResourceSpec operatorResourceSpec = operatorResourceRetriever.apply(operatorNodeId);
				final int operatorManagedMemoryWeight = operatorManagedMemoryWeightRetriever.apply(operatorNodeId);
				setManagedMemoryFractionForOperator(operatorResourceSpec, slotSharingGroup.getResourceSpec(), operatorManagedMemoryWeight,
					groupManagedMemoryWeight, operatorConfig);
			}

			// need to refresh the chained task configs because they are serialized
			final int headOperatorNodeId = vertexHeadOperators.get(jobVertexID);
			final StreamConfig vertexConfig = operatorConfigs.get(headOperatorNodeId);
			vertexConfig.setTransitiveChainedTaskConfigs(vertexChainedConfigs.get(headOperatorNodeId));
		}
	}

	private static void setManagedMemoryFractionForOperator(final ResourceSpec operatorResourceSpec, final ResourceSpec groupResourceSpec,
		final int operatorManagedMemoryWeight, final int groupManagedMemoryWeight, final StreamConfig operatorConfig) {

		final double managedMemoryFraction;

		if(groupResourceSpec.equals(ResourceSpec.UNKNOWN)) {
			managedMemoryFraction = groupManagedMemoryWeight > 0 ? getFractionRoundedDown(operatorManagedMemoryWeight,
				groupManagedMemoryWeight) : 0.0;
		} else {
			final long groupManagedMemoryBytes = groupResourceSpec.getManagedMemory().getBytes();
			managedMemoryFraction = groupManagedMemoryBytes > 0 ? getFractionRoundedDown(operatorResourceSpec.getManagedMemory().getBytes(),
				groupManagedMemoryBytes) : 0.0;
		}

		operatorConfig.setManagedMemoryFraction(managedMemoryFraction);
	}

	private static double getFractionRoundedDown(final long dividend, final long divisor) {
		return BigDecimal.valueOf(dividend).divide(BigDecimal.valueOf(divisor), MANAGED_MEMORY_FRACTION_SCALE, BigDecimal.ROUND_DOWN).doubleValue();
	}

	private void configureCheckpointing() {

		// TODO_MA 注释： 从 StreamGraph 获取到 CheckPointConfig
		CheckpointConfig cfg = streamGraph.getCheckpointConfig();

		// TODO_MA 注释： 获取间隔时间
		long interval = cfg.getCheckpointInterval();
		if(interval < MINIMAL_CHECKPOINT_TIME) {
			// interval of max value means disable periodic checkpoint
			interval = Long.MAX_VALUE;
		}

		//  --- configure the participating vertices ---

		// TODO_MA 注释： 所有 Source 节点
		// TODO_MA 注释： 需要 “触发 checkpoint” 的顶点，后续 CheckpointCoordinator 发起 checkpoint 时，只有这些点会收到 trigger checkpoint 消息。
		// collect the vertices that receive "trigger checkpoint" messages.
		// currently, these are all the sources
		List<JobVertexID> triggerVertices = new ArrayList<>();

		// TODO_MA 注释： 所有节点
		// TODO_MA 注释： 需要在 snapshot 完成后，向 CheckpointCoordinator 发送 ack 消息的顶点
		// collect the vertices that need to acknowledge the checkpoint
		// currently, these are all vertices
		List<JobVertexID> ackVertices = new ArrayList<>(jobVertices.size());

		// TODO_MA 注释： 所有节点
		// TODO_MA 注释： 需要在 checkpoint 完成后，收到 CheckpointCoordinator “notifyCheckpointComplete” 消息的顶点
		// collect the vertices that receive "commit checkpoint" messages
		// currently, these are all vertices
		List<JobVertexID> commitVertices = new ArrayList<>(jobVertices.size());

		// TODO_MA 注释： 解析 JobVertex 分别加入到 三类集合中
		for(JobVertex vertex : jobVertices.values()) {

			// TODO_MA 注释： 将所有 Source JobVertex 加入到 triggerVertices 集合中
			if(vertex.isInputVertex()) {
				triggerVertices.add(vertex.getID());
			}

			// TODO_MA 注释： 将所有 JobVertex 都加入到 commitVertices 和 ackVertices 集合中
			commitVertices.add(vertex.getID());
			ackVertices.add(vertex.getID());
		}

		//  --- configure options ---

		// TODO_MA 注释： 定义 Cleanup 策略
		CheckpointRetentionPolicy retentionAfterTermination;
		if(cfg.isExternalizedCheckpointsEnabled()) {
			CheckpointConfig.ExternalizedCheckpointCleanup cleanup = cfg.getExternalizedCheckpointCleanup();
			// Sanity check
			if(cleanup == null) {
				throw new IllegalStateException("Externalized checkpoints enabled, but no cleanup mode configured.");
			}
			retentionAfterTermination = cleanup
				.deleteOnCancellation() ? CheckpointRetentionPolicy.RETAIN_ON_FAILURE : CheckpointRetentionPolicy.RETAIN_ON_CANCELLATION;
		} else {
			retentionAfterTermination = CheckpointRetentionPolicy.NEVER_RETAIN_AFTER_TERMINATION;
		}

		/*************************************************
		 *
		 *  注释： 定义钩子
		 */
		//  --- configure the master-side checkpoint hooks ---
		final ArrayList<MasterTriggerRestoreHook.Factory> hooks = new ArrayList<>();
		for(StreamNode node : streamGraph.getStreamNodes()) {
			if(node.getOperatorFactory() instanceof UdfStreamOperatorFactory) {
				Function f = ((UdfStreamOperatorFactory) node.getOperatorFactory()).getUserFunction();

				if(f instanceof WithMasterCheckpointHook) {
					hooks.add(new FunctionMasterCheckpointHookFactory((WithMasterCheckpointHook<?>) f));
				}
			}
		}

		// because the hooks can have user-defined code, they need to be stored as
		// eagerly serialized values
		final SerializedValue<MasterTriggerRestoreHook.Factory[]> serializedHooks;
		if(hooks.isEmpty()) {
			serializedHooks = null;
		} else {
			try {
				MasterTriggerRestoreHook.Factory[] asArray = hooks.toArray(new MasterTriggerRestoreHook.Factory[hooks.size()]);
				serializedHooks = new SerializedValue<>(asArray);
			} catch(IOException e) {
				throw new FlinkRuntimeException("Trigger/restore hook is not serializable", e);
			}
		}

		/*************************************************
		 *
		 *  注释： 由于状态后端可以具有用户定义的代码，因此需要将其存储为热切的序列化值
		 */
		// because the state backend can have user-defined code, it needs to be stored as eagerly serialized value
		final SerializedValue<StateBackend> serializedStateBackend;
		if(streamGraph.getStateBackend() == null) {
			serializedStateBackend = null;
		} else {
			try {
				serializedStateBackend = new SerializedValue<StateBackend>(streamGraph.getStateBackend());
			} catch(IOException e) {
				throw new FlinkRuntimeException("State backend is not serializable", e);
			}
		}

		/*************************************************
		 *
		 *  注释： 将各种参数，集中到一个对象中来
		 */
		//  --- done, put it all together ---
		JobCheckpointingSettings settings = new JobCheckpointingSettings(triggerVertices, ackVertices, commitVertices,
			CheckpointCoordinatorConfiguration.builder().setCheckpointInterval(interval).setCheckpointTimeout(cfg.getCheckpointTimeout())
				.setMinPauseBetweenCheckpoints(cfg.getMinPauseBetweenCheckpoints()).setMaxConcurrentCheckpoints(cfg.getMaxConcurrentCheckpoints())
				.setCheckpointRetentionPolicy(retentionAfterTermination).setExactlyOnce(getCheckpointingMode(cfg) == CheckpointingMode.EXACTLY_ONCE)
				.setPreferCheckpointForRecovery(cfg.isPreferCheckpointForRecovery())
				.setTolerableCheckpointFailureNumber(cfg.getTolerableCheckpointFailureNumber())
				.setUnalignedCheckpointsEnabled(cfg.isUnalignedCheckpointsEnabled()).build(), serializedStateBackend, serializedHooks);

		/*************************************************
		 *
		 *  注释： 将 JobCheckpointingSettings 设置到 JobGraph
		 */
		jobGraph.setSnapshotSettings(settings);
	}

	/**
	 * A private class to help maintain the information of an operator chain during the recursive call in
	 * {@link #createChain(Integer, int, OperatorChainInfo)}.
	 */
	private static class OperatorChainInfo {
		private final Integer startNodeId;
		private final Map<Integer, byte[]> hashes;
		private final List<Map<Integer, byte[]>> legacyHashes;
		private final Map<Integer, List<Tuple2<byte[], byte[]>>> chainedOperatorHashes;
		private final List<OperatorCoordinator.Provider> coordinatorProviders;
		private final StreamGraph streamGraph;

		private OperatorChainInfo(int startNodeId, Map<Integer, byte[]> hashes, List<Map<Integer, byte[]>> legacyHashes, StreamGraph streamGraph) {
			this.startNodeId = startNodeId;
			this.hashes = hashes;
			this.legacyHashes = legacyHashes;
			this.chainedOperatorHashes = new HashMap<>();
			this.coordinatorProviders = new ArrayList<>();
			this.streamGraph = streamGraph;
		}

		byte[] getHash(Integer streamNodeId) {
			return hashes.get(streamNodeId);
		}

		private Integer getStartNodeId() {
			return startNodeId;
		}

		private List<Tuple2<byte[], byte[]>> getChainedOperatorHashes(int startNodeId) {
			return chainedOperatorHashes.get(startNodeId);
		}

		private List<OperatorCoordinator.Provider> getCoordinatorProviders() {
			return coordinatorProviders;
		}

		private OperatorID addNodeToChain(int currentNodeId, String operatorName) {
			List<Tuple2<byte[], byte[]>> operatorHashes = chainedOperatorHashes.computeIfAbsent(startNodeId, k -> new ArrayList<>());

			byte[] primaryHashBytes = hashes.get(currentNodeId);

			for(Map<Integer, byte[]> legacyHash : legacyHashes) {
				operatorHashes.add(new Tuple2<>(primaryHashBytes, legacyHash.get(currentNodeId)));
			}

			streamGraph.getStreamNode(currentNodeId).getCoordinatorProvider(operatorName, new OperatorID(getHash(currentNodeId)))
				.map(coordinatorProviders::add);
			return new OperatorID(primaryHashBytes);
		}

		private OperatorChainInfo newChain(Integer startNodeId) {
			return new OperatorChainInfo(startNodeId, hashes, legacyHashes, streamGraph);
		}
	}
}
