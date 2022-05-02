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

package org.apache.flink.examples.java.wordcount;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.MultipleParameterTool;
import org.apache.flink.examples.java.wordcount.util.WordCountData;
import org.apache.flink.util.Collector;
import org.apache.flink.util.Preconditions;

/**
 * Implements the "WordCount" program that computes a simple word occurrence histogram over text files.
 *
 * <p>The input is a plain text file with lines separated by newline characters.
 *
 * <p>Usage: <code>WordCount --input &lt;path&gt; --output &lt;path&gt;</code><br>
 * If no parameters are provided, the program is run with default data from {@link WordCountData}.
 *
 * <p>This example shows how to:
 * <ul>
 * <li> write a simple Flink program.
 * <li> use Tuple data types.
 * <li> write and use user-defined functions.
 * </ul>
 */
public class WordCount {

	// *************************************************************************
	//     PROGRAM
	// *************************************************************************
	public static void main(String[] args) throws Exception {

		final MultipleParameterTool params = MultipleParameterTool.fromArgs(args);

		/*************************************************
		 * 
		 *  注释： 第一步： 构建　ExecutionEnvironment
		 */
		// set up the execution environment
		final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

		// make parameters available in the web interface
		env.getConfig().setGlobalJobParameters(params);

		// get input data
		DataSet<String> text = null;
		if(params.has("input")) {
			// union all the inputs from text files
			for(String input : params.getMultiParameterRequired("input")) {

				/*************************************************
				 * 
				 *  注释： 第二步： 通过 env 读取数据得到 数据抽象： DataSet
				 */
				if(text == null) {
					text = env.readTextFile(input);
				} else {
					text = text.union(env.readTextFile(input));
				}
			}
			Preconditions.checkNotNull(text, "Input DataSet should not be null.");
		} else {
			// get default test text data
			System.out.println("Executing WordCount example with default input data set.");
			System.out.println("Use --input to specify file input.");
			text = WordCountData.getDefaultTextLineDataSet(env);
		}

		/*************************************************
		 * 
		 *  注释： 第三步： 调用各种算子执行计算
		 *  1、text.flatMap(new Tokenizer()) ==> FlatMapOperator
		 *  2、.groupBy(0).sum(1);  ==> AggregateOperator
		 */
		DataSet<Tuple2<String, Integer>> counts =
			// split up the lines in pairs (2-tuples) containing: (word,1)
			text.flatMap(new Tokenizer())
				// group by the tuple field "0" and sum up tuple field "1"
				.groupBy(0).sum(1);

		// emit result
		if(params.has("output")) {

			/*************************************************
			 * 
			 *  注释： 第四步： 把计算得到的结果数据 写出
			 */
			counts.writeAsCsv(params.get("output"), "\n", " ");

			/*************************************************
			 * 
			 *  注释： 到此为止，你其实能知道：
			 *  1、首先通过 InputFormat 加载数据源得到数据抽象对象： DataSource
			 *  2、针对 DataSource 调用 Transformation 操作执行计算，每个计算/算子 都是一个 Operator
			 *  3、当执行输出的时候，最终会构造一个 DataSink 返回
			 *  4、构造好了程序执行需要的 DataSource Operator DataSink， 但是程序并未执行
			 */

			/*************************************************
			 * 
			 *  注释： 第五步： 提交应用程序执行
			 */
			// execute program
			env.execute("WordCount Example");
		} else {
			System.out.println("Printing result to stdout. Use --output to specify output path.");
			counts.print();
		}

	}

	// *************************************************************************
	//     USER FUNCTIONS
	// *************************************************************************

	/**
	 * Implements the string tokenizer that splits sentences into words as a user-defined
	 * FlatMapFunction. The function takes a line (String) and splits it into
	 * multiple pairs in the form of "(word,1)" ({@code Tuple2<String, Integer>}).
	 */
	public static final class Tokenizer implements FlatMapFunction<String, Tuple2<String, Integer>> {

		@Override
		public void flatMap(String value, Collector<Tuple2<String, Integer>> out) {
			// normalize and split the line
			String[] tokens = value.toLowerCase().split("\\W+");

			// emit the pairs
			for(String token : tokens) {
				if(token.length() > 0) {
					out.collect(new Tuple2<>(token, 1));
				}
			}
		}
	}

}
