/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.examples.socket;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

/**
 * Implements a streaming windowed version of the "WordCount" program.
 *
 * <p>This program connects to a server socket and reads strings from the socket.
 * The easiest way to try this out is to open a text server (at port 12345)
 * using the <i>netcat</i> tool via
 * <pre>
 * nc -l 12345 on Linux or nc -l -p 12345 on Windows
 * </pre>
 * and run this example with the hostname and the port as arguments.
 */
@SuppressWarnings("serial")
public class SocketWindowWordCount {

	public static void main(String[] args) throws Exception {

		/*************************************************
		 * 
		 *  注释： 解析 host 和 port
		 */
		// the host and the port to connect to
		final String hostname;
		final int port;
		try {
			final ParameterTool params = ParameterTool.fromArgs(args);
			hostname = params.has("hostname") ? params.get("hostname") : "localhost";
			port = params.getInt("port");
		} catch(Exception e) {
			System.err.println(
				"No port specified. Please run 'SocketWindowWordCount " + "--hostname <hostname> --port <port>', where hostname (localhost by default) " + "and port is the address of the text server");
			System.err.println("To start a simple text server, run 'netcat -l <port>' and " + "type the input text into the command line");
			return;
		}

		/*************************************************
		 * 
		 *  注释： 获取 StreamExecutionEnvironment
		 *  它呢，还是 跟 Spark 中的 SparkContext 还是有区别的！
		 */
		// get the execution environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		/*************************************************
		 * 
		 *  注释： 加载数据源得到数据抽象：DataStream
		 *  其实最终，只是创建了一个 DataStreamSource 对象，然后把 SourceFunction（StreamOperator）和 StreamExecutionEnvironment
		 *  设置到了 DataStreamSource 中， DataStreamSource 是 DataStream 的子类
		 *  -
		 *  DataStream 的主要分类：
		 *  	DataStreamSource	流数据源
		 *  	DataStreamSink		流数据目的地
		 *  	KeyedStream			按key分组的数据流
		 *  	DataStream			普通数据流
		 *  -
		 *  关于函数理解：
		 *  	Function			传参
		 *  	Operator			Graph 中抽象概念
		 *  	Transformation		一种针对流的逻辑操作
		 *	 最终： Function ---> Operator ---> Transformation
		 */
		// get input data by connecting to the socket
		DataStream<String> text = env.socketTextStream(hostname, port, "\n");

		// parse the data, group it, window it, and aggregate the counts
		DataStream<WordWithCount> windowCounts = text

			// TODO_MA 注释： 讲算子生成 Transformation 加入到 Env 中的 transformations 集合中
			.flatMap(new FlatMapFunction<String, WordWithCount>() {
				@Override
				public void flatMap(String value, Collector<WordWithCount> out) {
					for(String word : value.split("\\s")) {

						// TODO_MA 注释： 每一个 Operator 的内部，都是通过一个 Output 对象来收集对应的处理结果数据的
						out.collect(new WordWithCount(word, 1L));
					}
				}
			})

			// TODO_MA 注释： 依然创建一个 DataStream(KeyedStream)
			.keyBy(value -> value.word).timeWindow(Time.seconds(5))

			// TODO_MA 注释：
			.reduce(new ReduceFunction<WordWithCount>() {
				@Override
				public WordWithCount reduce(WordWithCount a, WordWithCount b) {
					return new WordWithCount(a.word, a.count + b.count);
				}
			});

		// print the results with a single thread, rather than in parallel
		windowCounts.print().setParallelism(1);

		/*************************************************
		 * 
		 *  注释： 提交执行
		 */
		env.execute("Socket Window WordCount");
	}

	// ------------------------------------------------------------------------

	/**
	 * Data type for words with count.
	 */
	public static class WordWithCount {

		public String word;
		public long count;

		public WordWithCount() {
		}

		public WordWithCount(String word, long count) {
			this.word = word;
			this.count = count;
		}

		@Override
		public String toString() {
			return word + " : " + count;
		}
	}
}
