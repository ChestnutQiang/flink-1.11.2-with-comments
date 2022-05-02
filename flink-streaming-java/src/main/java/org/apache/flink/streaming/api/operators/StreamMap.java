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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

/**
 * A {@link StreamOperator} for executing {@link MapFunction MapFunctions}.
 */
@Internal
public class StreamMap<IN, OUT> extends AbstractUdfStreamOperator<OUT, MapFunction<IN, OUT>> implements OneInputStreamOperator<IN, OUT> {

	private static final long serialVersionUID = 1L;

	public StreamMap(MapFunction<IN, OUT> mapper) {
		super(mapper);
		chainingStrategy = ChainingStrategy.ALWAYS;
	}

	@Override
	public void processElement(StreamRecord<IN> element) throws Exception {

		/*************************************************
		 *
		 *  注释： 通过 Output 收集处理之后的结果数据
		 *  1、userFunction.map(element.getValue()) 这是用户自定义的 map 逻辑
		 *  2、然后计算完的结果替换掉当前 Opeartor 中的成员变量
		 *  3、然后被 StreamMap 这个 StreamOperator 继续收集
		 *  -
		 *  记住：因为当前是 map 操作，所以下一步肯定不是 shuffle
		 *  但是，如果是 shuffle 算子，则会执行输出了。
		 */
		output.collect(element.replace(userFunction.map(element.getValue())));
	}
}
