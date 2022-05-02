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

package org.apache.flink.runtime.jobgraph;

import org.apache.flink.runtime.io.network.partition.ResultPartitionType;

import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * // TODO_MA 注释： 中间数据集是由 operator 生成的数据集 - source 或 intermediate operation。
 * An intermediate data set is the data set produced by an operator - either a source or any intermediate operation.
 *
 * // TODO_MA 注释： 中间数据集可能会被其他操作员read ，materialized 或 discarded。
 * Intermediate data sets may be read by other operators, materialized, or discarded.
 *
 * // TODO_MA 注释： 表示JobVertex的输出，即经过operator处理产生的数据集。producer是JobVertex，consumer是JobEdge
 */
public class IntermediateDataSet implements java.io.Serializable {
	
	private static final long serialVersionUID = 1L;

	/*************************************************
	 *
	 *  注释： 当前 IntermediateDataSetID ID
	 */
	private final IntermediateDataSetID id; 		// the identifier

	/*************************************************
	 *
	 *  注释： 这个 IntermediateDataSet 是哪个 JobVertex 产出的
	 */
	private final JobVertex producer;				// the operation that produced this data set

	/*************************************************
	 *
	 *  注释： 当前这个 JobVertex 对应的下一个 JobVertex 可能不止一个。则中间的 JobEdge 也可能不止一个。
	 */
	private final List<JobEdge> consumers = new ArrayList<JobEdge>();

	// The type of partition to use at runtime
	private final ResultPartitionType resultType;
	
	// --------------------------------------------------------------------------------------------

	public IntermediateDataSet(IntermediateDataSetID id, ResultPartitionType resultType, JobVertex producer) {
		this.id = checkNotNull(id);
		this.producer = checkNotNull(producer);
		this.resultType = checkNotNull(resultType);
	}

	// --------------------------------------------------------------------------------------------
	
	public IntermediateDataSetID getId() {
		return id;
	}

	public JobVertex getProducer() {
		return producer;
	}
	
	public List<JobEdge> getConsumers() {
		return this.consumers;
	}

	public ResultPartitionType getResultType() {
		return resultType;
	}
	
	// --------------------------------------------------------------------------------------------
	
	public void addConsumer(JobEdge edge) {
		this.consumers.add(edge);
	}
	
	// --------------------------------------------------------------------------------------------
	
	@Override
	public String toString() {
		return "Intermediate Data Set (" + id + ")";
	}
}
