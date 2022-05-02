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
import org.apache.flink.streaming.api.transformations.ShuffleMode;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.util.OutputTag;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * // TODO_MA 注释： StreamEdge 是 streaming topology 中的一个 边，
 * // TODO_MA 注释： 这个边用来表示，上游 StreamNode 和 下游 StreamNode 之间的数据转移关系
 * // TODO_MA 注释： 由于 chaining/optimization 的缘故，有可能两个 job vertices 之间，一个 Edge 并不一定会建立连接
 * An edge in the streaming topology. One edge like this does not necessarily
 * gets converted to a connection between two job vertices (due to chaining/optimization).
 *
 * // TODO_MA 注释： 表示连接两个StreamNode的边
 */
@Internal
public class StreamEdge implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final long ALWAYS_FLUSH_BUFFER_TIMEOUT = 0L;

	// TODO_MA 注释： 当前 edge 的 ID
	private final String edgeId;

	// TODO_MA 注释： 上游 StreamNode ID
	private final int sourceId;

	// TODO_MA 注释： 下游 StreamNode ID
	private final int targetId;

	/**
	 * The type number of the input for co-tasks.
	 */
	private final int typeNumber;

	/**
	 * A list of output names that the target vertex listens to (if there is
	 * output selection).
	 */
	private final List<String> selectedNames;

	/**
	 * The side-output tag (if any) of this {@link StreamEdge}.
	 */
	private final OutputTag outputTag;

	/**
	 * // TODO_MA 注释： 该 StreamEdge 的分区规则
	 * The {@link StreamPartitioner} on this {@link StreamEdge}.
	 */
	private StreamPartitioner<?> outputPartitioner;

	/**
	 * The name of the operator in the source vertex.
	 */
	private final String sourceOperatorName;

	/**
	 * The name of the operator in the target vertex.
	 */
	private final String targetOperatorName;

	private final ShuffleMode shuffleMode;

	private long bufferTimeout;

	public StreamEdge(StreamNode sourceVertex, StreamNode targetVertex, int typeNumber, List<String> selectedNames, StreamPartitioner<?> outputPartitioner,
		OutputTag outputTag) {

		this(sourceVertex, targetVertex, typeNumber, ALWAYS_FLUSH_BUFFER_TIMEOUT, selectedNames, outputPartitioner, outputTag, ShuffleMode.UNDEFINED);
	}

	public StreamEdge(StreamNode sourceVertex, StreamNode targetVertex, int typeNumber, List<String> selectedNames, StreamPartitioner<?> outputPartitioner,
		OutputTag outputTag, ShuffleMode shuffleMode) {

		/*************************************************
		 *
		 *  注释： 创建 StreamEdge
		 */
		this(sourceVertex, targetVertex, typeNumber, sourceVertex.getBufferTimeout(), selectedNames, outputPartitioner, outputTag, shuffleMode);
	}

	public StreamEdge(StreamNode sourceVertex, StreamNode targetVertex, int typeNumber, long bufferTimeout, List<String> selectedNames,
		StreamPartitioner<?> outputPartitioner, OutputTag outputTag, ShuffleMode shuffleMode) {

		/*************************************************
		 *
		 *  注释： 创建 StreamEdge
		 */
		this.sourceId = sourceVertex.getId();
		this.targetId = targetVertex.getId();
		this.typeNumber = typeNumber;
		this.bufferTimeout = bufferTimeout;
		this.selectedNames = selectedNames;
		this.outputPartitioner = outputPartitioner;
		this.outputTag = outputTag;
		this.sourceOperatorName = sourceVertex.getOperatorName();
		this.targetOperatorName = targetVertex.getOperatorName();
		this.shuffleMode = checkNotNull(shuffleMode);

		// TODO_MA 注释： StreamEdge 的 ID 生成规则
		this.edgeId = sourceVertex + "_" + targetVertex + "_" + typeNumber + "_" + selectedNames + "_" + outputPartitioner;
	}

	public int getSourceId() {
		return sourceId;
	}

	public int getTargetId() {
		return targetId;
	}

	public int getTypeNumber() {
		return typeNumber;
	}

	public List<String> getSelectedNames() {
		return selectedNames;
	}

	public OutputTag getOutputTag() {
		return this.outputTag;
	}

	public StreamPartitioner<?> getPartitioner() {
		return outputPartitioner;
	}

	public ShuffleMode getShuffleMode() {
		return shuffleMode;
	}

	public void setPartitioner(StreamPartitioner<?> partitioner) {
		this.outputPartitioner = partitioner;
	}

	public void setBufferTimeout(long bufferTimeout) {
		checkArgument(bufferTimeout >= -1);
		this.bufferTimeout = bufferTimeout;
	}

	public long getBufferTimeout() {
		return bufferTimeout;
	}

	@Override
	public int hashCode() {
		return Objects.hash(edgeId, outputTag);
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) {
			return true;
		}
		if(o == null || getClass() != o.getClass()) {
			return false;
		}

		StreamEdge that = (StreamEdge) o;
		return Objects.equals(edgeId, that.edgeId) && Objects.equals(outputTag, that.outputTag);
	}

	@Override
	public String toString() {
		return "(" + (sourceOperatorName + "-" + sourceId) + " -> " + (targetOperatorName + "-" + targetId) + ", typeNumber=" + typeNumber + ", selectedNames=" + selectedNames + ", outputPartitioner=" + outputPartitioner + ", bufferTimeout=" + bufferTimeout + ", outputTag=" + outputTag + ')';
	}
}
