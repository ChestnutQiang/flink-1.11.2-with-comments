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

package org.apache.flink.runtime.jobgraph.jsonplan;

import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonFactory;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.StringWriter;
import java.util.List;


/*************************************************
 *
 *  注释： 生成的结果：
 *  -
 *  {"jid": "", "name": "", "nodes": [
 *  	{"id": "", "parallelism": "", "operator": "", "operator_strategy": "", "description": "", "inputs": ["num": "", "id": "", "exchange": ""]},
 *  	{"id": "", "parallelism": "", "operator": "", "operator_strategy": "", "description": "", "inputs": ["num": "", "id": "", "exchange": ""]},
 *  	{"id": "", "parallelism": "", "operator": "", "operator_strategy": "", "description": "", "inputs": ["num": "", "id": "", "exchange": ""]},
 *  	...
 *  ], "optimizer_properties": ""}
 */
public class JsonPlanGenerator {

	private static final String NOT_SET = "";
	private static final String EMPTY = "{}";

	public static String generatePlan(JobGraph jg) {
		try {
			final StringWriter writer = new StringWriter(1024);

			// TODO_MA 注释： 通过 JsonFactory 获取一个 JsonGenerator
			final JsonFactory factory = new JsonFactory();
			final JsonGenerator gen = factory.createGenerator(writer);

			// TODO_MA 注释： [{"key": "value", "key": "value", },{"key": []},{}]

			// TODO_MA 注释： 写入 {
			// start of everything
			gen.writeStartObject();
			// TODO_MA 注释： jsonPlan = {

			// TODO_MA 注释： 写入 jid -> jobID
			gen.writeStringField("jid", jg.getJobID().toString());
			// TODO_MA 注释： jsonPlan = {"jid": jobID, "name": name, "nodes":[{五个属性}, {}, {}]

			// TODO_MA 注释： 写入 name -> JobGraph name
			gen.writeStringField("name", jg.getName());

			// TODO_MA 注释： 开始写入 nodes -> JobVertex
			gen.writeArrayFieldStart("nodes");

			// TODO_MA 注释： 遍历每个 JobVertex 出来
			// info per vertex
			for(JobVertex vertex : jg.getVertices()) {

				// TODO_MA 注释： 获取 operator name
				String operator = vertex.getOperatorName() != null ? vertex.getOperatorName() : NOT_SET;

				// TODO_MA 注释： 获取 operator desc
				String operatorDescr = vertex.getOperatorDescription() != null ? vertex.getOperatorDescription() : NOT_SET;
				operatorDescr = StringEscapeUtils.escapeHtml4(operatorDescr);
				operatorDescr = operatorDescr.replace("\n", "<br/>");

				// TODO_MA 注释： 获取 optimizer props
				String optimizerProps = vertex.getResultOptimizerProperties() != null ? vertex.getResultOptimizerProperties() : EMPTY;

				// TODO_MA 注释： 获取 jobVertex name
				String description = vertex.getOperatorPrettyName() != null ? vertex.getOperatorPrettyName() : vertex.getName();
				// make sure the encoding is HTML pretty
				description = StringEscapeUtils.escapeHtml4(description);
				description = description.replace("\n", "<br/>");
				description = description.replace("\\", "&#92;");

				gen.writeStartObject();

				/*************************************************
				 *
				 *  注释： 写入核心5个属性
				 *  1、id -> jobvertex ID
				 *  2、parallelism -> jobvertex 并行度
				 *  3、operator -> 算子
				 *  4、operator_strategy -> 算子策略
				 *  5、description -> jobvertex 描述
				 */
				// write the core properties
				gen.writeStringField("id", vertex.getID().toString());
				gen.writeNumberField("parallelism", vertex.getParallelism());
				gen.writeStringField("operator", operator);
				gen.writeStringField("operator_strategy", operatorDescr);
				gen.writeStringField("description", description);

				if(!vertex.isInputVertex()) {
					// write the input edge properties
					gen.writeArrayFieldStart("inputs");

					List<JobEdge> inputs = vertex.getInputs();
					for(int inputNum = 0; inputNum < inputs.size(); inputNum++) {
						JobEdge edge = inputs.get(inputNum);
						if(edge.getSource() == null) {
							continue;
						}

						JobVertex predecessor = edge.getSource().getProducer();

						String shipStrategy = edge.getShipStrategyName();
						String preProcessingOperation = edge.getPreProcessingOperationName();
						String operatorLevelCaching = edge.getOperatorLevelCachingDescription();

						gen.writeStartObject();

						/*************************************************
						 *
						 *  注释： 在 inputs 节点下面，再写入 num 和  id 子节点
						 */
						gen.writeNumberField("num", inputNum);
						gen.writeStringField("id", predecessor.getID().toString());

						if(shipStrategy != null) {
							gen.writeStringField("ship_strategy", shipStrategy);
						}
						if(preProcessingOperation != null) {
							gen.writeStringField("local_strategy", preProcessingOperation);
						}
						if(operatorLevelCaching != null) {
							gen.writeStringField("caching", operatorLevelCaching);
						}

						/*************************************************
						 *
						 *  注释： 在 inputs 节点下面，再写入 exchange 子节点
						 */
						gen.writeStringField("exchange", edge.getSource().getResultType().name().toLowerCase());

						// TODO_MA 注释： 写入 }
						gen.writeEndObject();
					}

					// TODO_MA 注释： 写入 ]
					gen.writeEndArray();
				}

				// TODO_MA 注释： 最后写入一个节点： optimizer_properties -> optimizerProps
				// write the optimizer properties
				gen.writeFieldName("optimizer_properties");
				gen.writeRawValue(optimizerProps);

				// TODO_MA 注释： 对象结束 }
				gen.writeEndObject();
			}

			// TODO_MA 注释： 数组结束 ]
			// end of everything
			gen.writeEndArray();

			// TODO_MA 注释： 对象结束 }
			gen.writeEndObject();

			// TODO_MA 注释： 关闭
			gen.close();

			/*************************************************
			 *
			 *  注释： 返回 JSON 字符串
			 */
			return writer.toString();
		} catch(Exception e) {
			throw new RuntimeException("Failed to generate plan", e);
		}
	}
}
