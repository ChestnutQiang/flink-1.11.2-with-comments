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

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.writer.MultipleRecordWriters;
import org.apache.flink.runtime.io.network.api.writer.NonRecordWriter;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.io.network.api.writer.RecordWriterBuilder;
import org.apache.flink.runtime.io.network.api.writer.RecordWriterDelegate;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.api.writer.SingleRecordWriter;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.operators.coordination.OperatorEvent;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.runtime.state.CheckpointStorageWorkerView;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.StateBackendLoader;
import org.apache.flink.runtime.taskmanager.DispatcherThreadFactory;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.runtime.util.FatalExitExceptionHandler;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.operators.MailboxExecutor;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializer;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializerImpl;
import org.apache.flink.streaming.runtime.io.RecordWriterOutput;
import org.apache.flink.streaming.runtime.io.StreamInputProcessor;
import org.apache.flink.streaming.runtime.partitioner.ConfigurableStreamPartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorFactory;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxProcessor;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailboxImpl;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.function.RunnableWithException;
import org.apache.flink.util.function.ThrowingRunnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * // TODO_MA 注释： 所有 StreamTask 的基类。Task 是由 TaskManager 部署并执行的本地处理单元。
 * // TODO_MA 注释： 每个 Task 运行一个或多个{@link StreamOperator}，这些 StreamOperator 形成 Task 的 OperatorChain
 * // TODO_MA 注释： 链接在一起的 Operators 在同一线程中并因此在同一 stream partition 上同步执行。
 * // TODO_MA 注释： 这些链的常见情况是连续的 map / flatmap / filter 任务。
 * Base class for all streaming tasks. A task is the unit of local processing that is deployed
 * and executed by the TaskManagers. Each task runs one or more {@link StreamOperator}s which form
 * the Task's operator chain. Operators that are chained together execute synchronously in the
 * same thread and hence on the same stream partition. A common case for these chains
 * are successive map/flatmap/filter tasks.
 *
 * // TODO_MA 注释： 任务链包含一个 "head" operator 和多个 chained operators
 * // TODO_MA 注释： StreamTask 用 head operator 来定义，分为 one-input 和 two-input两种 Task，以及 sources，iteration heads 和 iteration tails。
 * <p>The task chain contains one "head" operator and multiple chained operators.
 * The StreamTask is specialized for the type of the head operator: one-input and two-input tasks,
 * as well as for sources, iteration heads and iteration tails.
 *
 * // TODO_MA 注释： Task类负责处理由头运算符读取的流以及在运算符链末端由运算符产生的流的设置。请注意，链条可能会分叉，因此具有多个末端。
 * <p>The Task class deals with the setup of the streams read by the head operator, and the streams
 * produced by the operators at the ends of the operator chain. Note that the chain may fork and
 * thus have multiple ends.
 *
 * <p>The life cycle of the task is set up as follows:
 * <pre>{@code
 *  -- setInitialState -> provides state of all operators in the chain
 *
 *  -- invoke()
 *        |
 *        +----> Create basic utils (config, etc) and load the chain of operators
 *        +----> operators.setup()
 *        +----> task specific init()
 *        +----> initialize-operator-states()
 *        +----> open-operators()
 *        +----> run()
 *        +----> close-operators()
 *        +----> dispose-operators()
 *        +----> common cleanup
 *        +----> task specific cleanup()
 * }</pre>
 *
 * <p>The {@code StreamTask} has a lock object called {@code lock}. All calls to methods on a
 * {@code StreamOperator} must be synchronized on this lock object to ensure that no methods
 * are called concurrently.
 *
 * @param <OUT>
 * @param <OP>
 */
@Internal
public abstract class StreamTask<OUT, OP extends StreamOperator<OUT>> extends AbstractInvokable implements AsyncExceptionHandler {

	/**
	 * The thread group that holds all trigger timer threads.
	 */
	public static final ThreadGroup TRIGGER_THREAD_GROUP = new ThreadGroup("Triggers");

	/**
	 * The logger used by the StreamTask and its subclasses.
	 */
	protected static final Logger LOG = LoggerFactory.getLogger(StreamTask.class);

	// ------------------------------------------------------------------------

	/**
	 * All actions outside of the task {@link #mailboxProcessor mailbox} (i.e. performed by another thread) must be executed through this executor
	 * to ensure that we don't have concurrent method calls that void consistent checkpoints.
	 * <p>CheckpointLock is superseded by {@link MailboxExecutor}, with
	 * {@link StreamTaskActionExecutor.SynchronizedStreamTaskActionExecutor SynchronizedStreamTaskActionExecutor}
	 * to provide lock to {@link SourceStreamTask}. </p>
	 */
	private final StreamTaskActionExecutor actionExecutor;

	/**
	 * The input processor. Initialized in {@link #init()} method.
	 */
	@Nullable
	protected StreamInputProcessor inputProcessor;

	/**
	 * the head operator that consumes the input streams of this task.
	 */
	protected OP headOperator;

	/**
	 * The chain of operators executed by this task.
	 */
	protected OperatorChain<OUT, OP> operatorChain;

	/**
	 * The configuration of this streaming task.
	 */
	protected final StreamConfig configuration;

	/**
	 * Our state backend. We use this to create checkpoint streams and a keyed state backend.
	 */
	protected final StateBackend stateBackend;

	private final SubtaskCheckpointCoordinator subtaskCheckpointCoordinator;

	/**
	 * // TODO_MA 注释： 内部 {@link TimerService} 用于定义当前处理时间
	 * // TODO_MA 注释： （默认= {@code System.currentTimeMillis（）}），并注册用于将来执行任务的计时器。
	 * The internal {@link TimerService} used to define the current
	 * processing time (default = {@code System.currentTimeMillis()}) and
	 * register timers for tasks to be executed in the future.
	 */
	protected final TimerService timerService;

	/**
	 * The currently active background materialization threads.
	 */
	private final CloseableRegistry cancelables = new CloseableRegistry();

	private final StreamTaskAsyncExceptionHandler asyncExceptionHandler;

	/**
	 * Flag to mark the task "in operation", in which case check needs to be initialized to true,
	 * so that early cancel() before invoke() behaves correctly.
	 */
	private volatile boolean isRunning;

	/**
	 * Flag to mark this task as canceled.
	 */
	private volatile boolean canceled;

	/**
	 * Flag to mark this task as failing, i.e. if an exception has occurred inside {@link #invoke()}.
	 */
	private volatile boolean failing;

	private boolean disposedOperators;

	/**
	 * Thread pool for async snapshot workers.
	 */
	private final ExecutorService asyncOperationsThreadPool;

	private final RecordWriterDelegate<SerializationDelegate<StreamRecord<OUT>>> recordWriter;

	/*************************************************
	 * 
	 *  注释： MailBox 处理器。MailBox 的核心处理线程
	 *
	 *
	 */
	protected final MailboxProcessor mailboxProcessor;

	/*************************************************
	 * 
	 *  注释： Mail 执行器，其实就是一个线程池
	 *  负责向 MailBox 提交 task 任务
	 */
	final MailboxExecutor mainMailboxExecutor;

	/**
	 * TODO it might be replaced by the global IO executor on TaskManager level future.
	 */
	private final ExecutorService channelIOExecutor;

	private Long syncSavepointId = null;

	private long latestAsyncCheckpointStartDelayNanos;

	// ------------------------------------------------------------------------

	/**
	 * Constructor for initialization, possibly with initial state (recovery / savepoint / etc).
	 *
	 * @param env The task environment for this task.
	 */
	protected StreamTask(Environment env) throws Exception {

		// TODO_MA 注释： 调用 StreamTask 构造
		this(env, null);
	}

	/**
	 * Constructor for initialization, possibly with initial state (recovery / savepoint / etc).
	 *
	 * @param env          The task environment for this task.
	 * @param timerService Optionally, a specific timer service to use.
	 */
	protected StreamTask(Environment env, @Nullable TimerService timerService) throws Exception {

		/*************************************************
		 * 
		 *  注释： 调用重载构造
		 */
		this(env, timerService, FatalExitExceptionHandler.INSTANCE);
	}

	protected StreamTask(Environment environment, @Nullable TimerService timerService,
		Thread.UncaughtExceptionHandler uncaughtExceptionHandler) throws Exception {

		/*************************************************
		 * 
		 *  注释： 调用重载构造
		 *  注意第三个参数： StreamTaskActionExecutor
		 */
		this(environment, timerService, uncaughtExceptionHandler, StreamTaskActionExecutor.IMMEDIATE);
	}

	/**
	 * Constructor for initialization, possibly with initial state (recovery / savepoint / etc).
	 *
	 * <p>This constructor accepts a special {@link TimerService}. By default (and if
	 * null is passes for the timer service) a {@link SystemProcessingTimeService DefaultTimerService}
	 * will be used.
	 *
	 * @param environment              The task environment for this task.
	 * @param timerService             Optionally, a specific timer service to use.
	 * @param uncaughtExceptionHandler to handle uncaught exceptions in the async operations thread pool
	 * @param actionExecutor           a mean to wrap all actions performed by this task thread. Currently, only SynchronizedActionExecutor can be used to preserve locking semantics.
	 */
	protected StreamTask(Environment environment, @Nullable TimerService timerService, Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
		StreamTaskActionExecutor actionExecutor) throws Exception {

		/*************************************************
		 * 
		 *  注释： 构造 StreamTask 实例
		 *  注意最后一个参数：
		 *  每个 StreamTask 都会生成一个 TaskMailboxImpl 对象
		 */
		this(environment, timerService, uncaughtExceptionHandler, actionExecutor, new TaskMailboxImpl(Thread.currentThread()));
	}

	/*************************************************
	 * 
	 *  注释： StreamTask 最终的构造方法
	 */
	protected StreamTask(Environment environment, @Nullable TimerService timerService, Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
		StreamTaskActionExecutor actionExecutor, TaskMailbox mailbox) throws Exception {

		super(environment);

		this.configuration = new StreamConfig(getTaskConfiguration());

		/*************************************************
		 * 
		 *  注释： 创建 RecordWriter, 大概率是：ChannelSelectorRecordWriter， 也有可能是个 BroadcastRecordWriter
		 */
		this.recordWriter = createRecordWriterDelegate(configuration, environment);

		// TODO_MA 注释： SynchronizedStreamTaskActionExecutor
		this.actionExecutor = Preconditions.checkNotNull(actionExecutor);

		/*************************************************
		 * 
		 *  注释： 初始化 StreamTask 的时候，初始化 MailboxProcessor， 同时，执行 StreamTask 的 processInput() 方法
		 *  1、如果为 SourceStreamTask 的话，processInput 方法会启动 SourceStreamTask 的 sourceThread
		 *  2、如果为其他的非 SourceStreamTask 的话，则根据情况（StreamOneInputProcessor 或者 StreamTwoInputProcessor）处理输入情况
		 *  -
		 *  第二个参数：TaskMailboxImpl
		 *  第三个参数：SynchronizedStreamTaskActionExecutor
		 */
		this.mailboxProcessor = new MailboxProcessor(this::processInput, mailbox, actionExecutor);

		/*************************************************
		 * 
		 *  注释： 当这里执行完了， SourceStreamTask 的接收数据线程，就卡在接收数据哪儿了。
		 */

		this.mailboxProcessor.initMetric(environment.getMetricGroup());
		this.mainMailboxExecutor = mailboxProcessor.getMainMailboxExecutor();     // TODO_MA 注释： MailboxExecutorImpl
		this.asyncExceptionHandler = new StreamTaskAsyncExceptionHandler(environment);
		this.asyncOperationsThreadPool = Executors.newCachedThreadPool(new ExecutorThreadFactory("AsyncOperations", uncaughtExceptionHandler));

		/*************************************************
		 * 
		 *  注释： 创建 StateBackend
		 *  根据参数 state.backend 来创建响应的 StateBackend
		 *  -
		 *  1、MemoryStateBackend 把状态存储在job manager的内存中
		 *  2、FsStateBackend 把状态存在文件系统中，有可能是本地文件系统，也有可能是HDFS、S3等分布式文件系统
		 *  3、RocksDBStateBackend 把状态存在 RocksDB 中
		 *  -
		 *  按照我们的配置，一般获取到的是 FsStateBackend
		 */
		this.stateBackend = createStateBackend();

		/*************************************************
		 * 
		 *  注释： 初始化 SubtaskCheckpointCoordinatorImpl
		 */
		this.subtaskCheckpointCoordinator = new SubtaskCheckpointCoordinatorImpl(

			/*************************************************
			 * 
			 *  注释： 创建 CheckpointStorage
			 *  1、FsStateBackend = FsCheckpointStorage
			 */
			stateBackend.createCheckpointStorage(getEnvironment().getJobID()), getName(), actionExecutor, getCancelables(),
			getAsyncOperationsThreadPool(), getEnvironment(), this, configuration.isUnalignedCheckpointsEnabled(), this::prepareInputSnapshot);

		// TODO_MA 注释： 时间语义服务 初始化
		// TODO_MA 注释： ProcessingTime， EventTime， InjestioniTime
		// if the clock is not already set, then assign a default TimeServiceProvider
		if(timerService == null) {
			ThreadFactory timerThreadFactory = new DispatcherThreadFactory(TRIGGER_THREAD_GROUP, "Time Trigger for " + getName());
			this.timerService = new SystemProcessingTimeService(this::handleTimerException, timerThreadFactory);
		} else {
			this.timerService = timerService;
		}

		/*************************************************
		 * 
		 *  注释： 创建 Channel 的 IO 线程池
		 */
		this.channelIOExecutor = Executors.newSingleThreadExecutor(new ExecutorThreadFactory("channel-state-unspilling"));
	}

	private CompletableFuture<Void> prepareInputSnapshot(ChannelStateWriter channelStateWriter, long checkpointId) throws IOException {
		if(inputProcessor == null) {
			return FutureUtils.completedVoidFuture();
		}

		/*************************************************
		 * 
		 *  注释： 处理快照
		 */
		return inputProcessor.prepareSnapshot(channelStateWriter, checkpointId);
	}

	SubtaskCheckpointCoordinator getCheckpointCoordinator() {
		return subtaskCheckpointCoordinator;
	}

	// ------------------------------------------------------------------------
	//  Life cycle methods for specific implementations
	// ------------------------------------------------------------------------

	protected abstract void init() throws Exception;

	protected void cancelTask() throws Exception {
	}

	protected void cleanup() throws Exception {
		if(inputProcessor != null) {
			inputProcessor.close();
		}
	}

	/**
	 * This method implements the default action of the task (e.g. processing one event from the input). Implementations
	 * should (in general) be non-blocking.
	 * // TODO_MA 注释： 这个方法执行这个 task 默认的 action
	 *
	 * @param controller controller object for collaborative interaction between the action and the stream task.
	 * @throws Exception on any problems in the action.
	 */
	protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {

		/*************************************************
		 * 
		 *  注释： 处理输入
		 *  inputProcessor = OneInputxxxx
		 */
		InputStatus status = inputProcessor.processInput();

		// TODO_MA 注释：  如果输入还有数据，并且 writer 是可用的，这里就直接返回了
		if(status == InputStatus.MORE_AVAILABLE && recordWriter.isAvailable()) {
			return;
		}

		// TODO_MA 注释： 输入已经处理完了，会调用这个方法
		if(status == InputStatus.END_OF_INPUT) {
			controller.allActionsCompleted();
			return;
		}
		CompletableFuture<?> jointFuture = getInputOutputJointFuture(status);

		// TODO_MA 注释： 告诉 MailBox 先暂停 loop
		MailboxDefaultAction.Suspension suspendedDefaultAction = controller.suspendDefaultAction();

		// TODO_MA 注释： 等待 future 完成后，继续 mailbox loop（等待 input 和 output 可用后，才会继续）
		jointFuture.thenRun(suspendedDefaultAction::resume);
	}

	/**
	 * Considers three scenarios to combine input and output futures:
	 * 1. Both input and output are unavailable.
	 * 2. Only input is unavailable.
	 * 3. Only output is unavailable.
	 */
	@VisibleForTesting
	CompletableFuture<?> getInputOutputJointFuture(InputStatus status) {
		if(status == InputStatus.NOTHING_AVAILABLE && !recordWriter.isAvailable()) {
			return CompletableFuture.allOf(inputProcessor.getAvailableFuture(), recordWriter.getAvailableFuture());
		} else if(status == InputStatus.NOTHING_AVAILABLE) {
			return inputProcessor.getAvailableFuture();
		} else {
			return recordWriter.getAvailableFuture();
		}
	}

	private void resetSynchronousSavepointId() {
		syncSavepointId = null;
	}

	private void setSynchronousSavepointId(long checkpointId) {
		Preconditions.checkState(syncSavepointId == null, "at most one stop-with-savepoint checkpoint at a time is allowed");
		syncSavepointId = checkpointId;
	}

	@VisibleForTesting
	OptionalLong getSynchronousSavepointId() {
		return syncSavepointId != null ? OptionalLong.of(syncSavepointId) : OptionalLong.empty();
	}

	private boolean isSynchronousSavepointId(long checkpointId) {
		return syncSavepointId != null && syncSavepointId == checkpointId;
	}

	private void runSynchronousSavepointMailboxLoop() throws Exception {
		assert syncSavepointId != null;

		MailboxExecutor mailboxExecutor = mailboxProcessor.getMailboxExecutor(TaskMailbox.MAX_PRIORITY);

		while(!canceled && syncSavepointId != null) {
			mailboxExecutor.yield();
		}
	}

	/**
	 * Emits the {@link org.apache.flink.streaming.api.watermark.Watermark#MAX_WATERMARK MAX_WATERMARK}
	 * so that all registered timers are fired.
	 *
	 * <p>This is used by the source task when the job is {@code TERMINATED}. In the case,
	 * we want all the timers registered throughout the pipeline to fire and the related
	 * state (e.g. windows) to be flushed.
	 *
	 * <p>For tasks other than the source task, this method does nothing.
	 */
	protected void advanceToEndOfEventTime() throws Exception {

	}

	/**
	 * Instructs the task to go through its normal termination routine, i.e. exit the run-loop
	 * and call {@link StreamOperator#close()} and {@link StreamOperator#dispose()} on its operators.
	 *
	 * <p>This is used by the source task to get out of the run-loop when the job is stopped with a savepoint.
	 *
	 * <p>For tasks other than the source task, this method does nothing.
	 */
	protected void finishTask() throws Exception {

	}

	// ------------------------------------------------------------------------
	//  Core work methods of the Stream Task
	// ------------------------------------------------------------------------

	public StreamTaskStateInitializer createStreamTaskStateInitializer() {

		/*************************************************
		 * 
		 *  注释： 初始化得到一个 StreamTaskStateInitializerImpl 组件
		 */
		return new StreamTaskStateInitializerImpl(getEnvironment(), stateBackend);
	}

	protected Counter setupNumRecordsInCounter(StreamOperator streamOperator) {
		try {
			return ((OperatorMetricGroup) streamOperator.getMetricGroup()).getIOMetricGroup().getNumRecordsInCounter();
		} catch(Exception e) {
			LOG.warn("An exception occurred during the metrics setup.", e);
			return new SimpleCounter();
		}
	}

	protected void beforeInvoke() throws Exception {
		disposedOperators = false;
		LOG.debug("Initializing {}.", getName());

		/*************************************************
		 * 
		 *  注释： 构建 OperatorChain 对象，里面会做很多事情
		 *  初始化 output 输出对象
		 *  主要做三件事情：
		 *  1、调用createStreamOutput（）创建对应的下游输出RecordWriterOutput
		 *  2、调用createOutputCollector（）将优化逻辑计划当中Chain中的StreamConfig（也就是数据）写入到第三步创建的RecordWriterOutput中
		 *  3、通过调用getChainedOutputs（）输出结果RecordWriterOutput
		 */
		operatorChain = new OperatorChain<>(this, recordWriter);

		// TODO_MA 注释： 获取 OperatorChain 的第一个 Operator
		// TODO_MA 注释： 可以认为 接收数据线程中，要用到的 headOpeartor 终于被初始化了。
		// TODO_MA 注释： 其实到此为止，可以认为，在当前 OperatorChain 中要用到的各种组件都已经创建好了，可以接收数据，然后开始流式处理了。
		headOperator = operatorChain.getHeadOperator();

		/*************************************************
		 * 
		 *  注释： 执行 StreamTask 的初始化
		 *  1、可能是 SourceStreamTask， 对于 SourceStreamTask 来说，只是注册一个 savepoint 钩子
		 *  2、也可能是 OneInputStreamTask
		 */
		// task specific initialization
		init();

		// save the work of reloading state, etc, if the task is already canceled
		if(canceled) {
			throw new CancelTaskException();
		}

		// -------- Invoke --------
		LOG.debug("Invoking {}", getName());

		// we need to make sure that any triggers scheduled in open() cannot be
		// executed before all operators are opened
		actionExecutor.runThrowing(() -> {

			/*************************************************
			 * 
			 *  注释： 状态恢复入口
			 */
			// both the following operations are protected by the lock
			// so that we avoid race conditions in the case that initializeState()
			// registers a timer, that fires before the open() is called.
			operatorChain.initializeStateAndOpenOperators(createStreamTaskStateInitializer());

			/*************************************************
			 * 
			 *  注释： 初始化 Mail
			 *  这个地方主要是初始化 InputGate 等输入相关的细节
			 */
			readRecoveredChannelState();
		});

		isRunning = true;
	}

	private void readRecoveredChannelState() throws IOException, InterruptedException {
		ChannelStateReader reader = getEnvironment().getTaskStateManager().getChannelStateReader();
		if(!reader.hasChannelStates()) {
			requestPartitions();
			return;
		}

		ResultPartitionWriter[] writers = getEnvironment().getAllWriters();
		if(writers != null) {

			/*************************************************
			 * 
			 *  注释： 遍历每个 ResultPartition， 把每个 ResultPartition 的状态恢复出来
			 */
			for(ResultPartitionWriter writer : writers) {

				/*************************************************
				 * 
				 *  注释： 恢复 ResultPartition 的状态
				 */
				writer.readRecoveredState(reader);
			}
		}

		// It would get possible benefits to recovery input side after output side, which guarantees the
		// output can request more floating buffers from global firstly.
		InputGate[] inputGates = getEnvironment().getAllInputGates();
		if(inputGates != null && inputGates.length > 0) {
			CompletableFuture[] futures = new CompletableFuture[inputGates.length];
			for(int i = 0; i < inputGates.length; i++) {
				futures[i] = inputGates[i].readRecoveredState(channelIOExecutor, reader);
			}

			/*************************************************
			 * 
			 *  注释：
			 */
			// Note that we must request partition after all the single gates finished recovery.
			CompletableFuture.allOf(futures)
				.thenRun(() -> mainMailboxExecutor.execute(this::requestPartitions, "Input gates request partitions"));
		}
	}

	private void requestPartitions() throws IOException {
		InputGate[] inputGates = getEnvironment().getAllInputGates();
		if(inputGates != null) {

			// TODO_MA 注释： 遍历每个 InputGate
			for(InputGate inputGate : inputGates) {

				/*************************************************
				 * 
				 *  注释： 获取数据源
				 */
				inputGate.requestPartitions();
			}
		}
	}

	/*************************************************
	 * 
	 *  注释： 分为四步走
	 */
	@Override
	public final void invoke() throws Exception {
		try {

			/*************************************************
			 * 
			 *  注释： 第一步： 初始化输入
			 */
			beforeInvoke();

			// final check to exit early before starting to run
			if(canceled) {
				throw new CancelTaskException();
			}

			/*************************************************
			 * 
			 *  注释： Task 开始工作
			 *  执行这句代码的时候，还是在 Task 所在的那个线程中执行的。
			 */
			// let the task do its work
			runMailboxLoop();

			// if this left the run() method cleanly despite the fact that this was canceled,
			// make sure the "clean shutdown" is not attempted
			if(canceled) {
				throw new CancelTaskException();
			}

			/*************************************************
			 * 
			 *  注释： 第三件事
			 */
			afterInvoke();
		} catch(Throwable invokeException) {
			failing = !canceled;
			try {
				cleanUpInvoke();
			} catch(Throwable cleanUpException) {
				Throwable throwable = ExceptionUtils.firstOrSuppressed(cleanUpException, invokeException);
				ExceptionUtils.rethrowException(throwable);
			}
			ExceptionUtils.rethrowException(invokeException);
		}

		/*************************************************
		 * 
		 *  注释： 各种关闭 close() shutDown()
		 */
		cleanUpInvoke();
	}

	@VisibleForTesting
	public boolean runMailboxStep() throws Exception {
		return mailboxProcessor.runMailboxStep();
	}

	private void runMailboxLoop() throws Exception {

		/*************************************************
		 * 
		 *  注释： 通过 MailboxProcessor 来轮询 MailBox 处理 Mail
		 */
		mailboxProcessor.runMailboxLoop();
	}

	/*************************************************
	 * 
	 *  注释： StreamTask 运行之后
	 */
	protected void afterInvoke() throws Exception {
		LOG.debug("Finished task {}", getName());
		getCompletionFuture().exceptionally(unused -> null).join();

		final CompletableFuture<Void> timersFinishedFuture = new CompletableFuture<>();

		// TODO_MA 注释： 连锁关闭所有 operators
		// close all operators in a chain effect way
		operatorChain.closeOperators(actionExecutor);

		// TODO_MA 注释： 主要是把 actionExecutor 的状态置为 false，阻止继续接收 mail
		// make sure no further checkpoint and notification actions happen.
		// at the same time, this makes sure that during any "regular" exit where still
		actionExecutor.runThrowing(() -> {

			// make sure no new timers can come
			FutureUtils.forward(timerService.quiesce(), timersFinishedFuture);

			// let mailbox execution reject all new letters from this point
			mailboxProcessor.prepareClose();

			// only set the StreamTask to not running after all operators have been closed! See FLINK-7430
			isRunning = false;
		});

		/*************************************************
		 * 
		 *  注释： 然后处理完剩下的 Mail
		 */
		// processes the remaining mails; no new mails can be enqueued
		mailboxProcessor.drain();

		// make sure all timers finish
		timersFinishedFuture.get();

		LOG.debug("Closed operators for task {}", getName());

		/*************************************************
		 * 
		 *  注释： 刷出数据
		 */
		// make sure all buffered data is flushed
		operatorChain.flushOutputs();

		// TODO_MA 注释： 尝试处置运算符，以使 dispose 调用中的失败仍然使计算失败
		// make an attempt to dispose the operators such that failures in the dispose call still let the computation fail
		disposeAllOperators(false);
		disposedOperators = true;
	}

	/*************************************************
	 * 
	 *  注释： 生命周期的第四个方法
	 */
	protected void cleanUpInvoke() throws Exception {

		getCompletionFuture().exceptionally(unused -> null).join();
		// clean up everything we initialized
		isRunning = false;

		// Now that we are outside the user code, we do not want to be interrupted further
		// upon cancellation. The shutdown logic below needs to make sure it does not issue calls
		// that block and stall shutdown.
		// Additionally, the cancellation watch dog will issue a hard-cancel (kill the TaskManager
		// process) as a backup in case some shutdown procedure blocks outside our control.
		setShouldInterruptOnCancel(false);

		// clear any previously issued interrupt for a more graceful shutdown
		Thread.interrupted();

		// stop all timers and threads
		tryShutdownTimerService();

		// stop all asynchronous checkpoint threads
		try {
			cancelables.close();
			shutdownAsyncThreads();
		} catch(Throwable t) {
			// catch and log the exception to not replace the original exception
			LOG.error("Could not shut down async checkpoint threads", t);
		}

		// we must! perform this cleanup
		try {
			cleanup();
		} catch(Throwable t) {
			// catch and log the exception to not replace the original exception
			LOG.error("Error during cleanup of stream task", t);
		}

		// if the operators were not disposed before, do a hard dispose
		disposeAllOperators(true);

		// release the output resources. this method should never fail.
		if(operatorChain != null) {
			// beware: without synchronization, #performCheckpoint() may run in
			//         parallel and this call is not thread-safe
			actionExecutor.run(() -> operatorChain.releaseOutputs());
		} else {
			// failed to allocate operatorChain, clean up record writers
			recordWriter.close();
		}

		try {
			channelIOExecutor.shutdown();
		} catch(Throwable t) {
			LOG.error("Error during shutdown the channel state unspill executor", t);
		}

		mailboxProcessor.close();
	}

	protected CompletableFuture<Void> getCompletionFuture() {
		return FutureUtils.completedVoidFuture();
	}

	@Override
	public final void cancel() throws Exception {
		isRunning = false;
		canceled = true;

		// the "cancel task" call must come first, but the cancelables must be
		// closed no matter what
		try {
			cancelTask();
		} finally {
			getCompletionFuture().whenComplete((unusedResult, unusedError) -> {
				// WARN: the method is called from the task thread but the callback can be invoked from a different thread
				mailboxProcessor.allActionsCompleted();
				try {
					cancelables.close();
				} catch(IOException e) {
					throw new CompletionException(e);
				}
			});
		}
	}

	public MailboxExecutorFactory getMailboxExecutorFactory() {
		return this.mailboxProcessor::getMailboxExecutor;
	}

	public final boolean isRunning() {
		return isRunning;
	}

	public final boolean isCanceled() {
		return canceled;
	}

	public final boolean isFailing() {
		return failing;
	}

	private void shutdownAsyncThreads() throws Exception {
		if(!asyncOperationsThreadPool.isShutdown()) {
			asyncOperationsThreadPool.shutdownNow();
		}
	}

	/**
	 * Execute @link StreamOperator#dispose()} of each operator in the chain of this
	 * {@link StreamTask}. Disposing happens from <b>tail to head</b> operator in the chain.
	 */
	private void disposeAllOperators(boolean logOnlyErrors) throws Exception {
		if(operatorChain != null && !disposedOperators) {
			for(StreamOperatorWrapper<?, ?> operatorWrapper : operatorChain.getAllOperators(true)) {
				StreamOperator<?> operator = operatorWrapper.getStreamOperator();
				if(!logOnlyErrors) {
					operator.dispose();
				} else {
					try {
						operator.dispose();
					} catch(Exception e) {
						LOG.error("Error during disposal of stream operator.", e);
					}
				}
			}
			disposedOperators = true;
		}
	}

	/**
	 * The finalize method shuts down the timer. This is a fail-safe shutdown, in case the original
	 * shutdown method was never called.
	 *
	 * <p>This should not be relied upon! It will cause shutdown to happen much later than if manual
	 * shutdown is attempted, and cause threads to linger for longer than needed.
	 */
	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		if(!timerService.isTerminated()) {
			LOG.info("Timer service is shutting down.");
			timerService.shutdownService();
		}

		cancelables.close();
	}

	boolean isSerializingTimestamps() {
		TimeCharacteristic tc = configuration.getTimeCharacteristic();
		return tc == TimeCharacteristic.EventTime | tc == TimeCharacteristic.IngestionTime;
	}

	// ------------------------------------------------------------------------
	//  Access to properties and utilities
	// ------------------------------------------------------------------------

	/**
	 * Gets the name of the task, in the form "taskname (2/5)".
	 *
	 * @return The name of the task.
	 */
	public final String getName() {
		return getEnvironment().getTaskInfo().getTaskNameWithSubtasks();
	}

	/**
	 * Gets the name of the task, appended with the subtask indicator and execution id.
	 *
	 * @return The name of the task, with subtask indicator and execution id.
	 */
	String getTaskNameWithSubtaskAndId() {
		return getEnvironment().getTaskInfo().getTaskNameWithSubtasks() + " (" + getEnvironment().getExecutionId() + ')';
	}

	public CheckpointStorageWorkerView getCheckpointStorage() {
		return subtaskCheckpointCoordinator.getCheckpointStorage();
	}

	public StreamConfig getConfiguration() {
		return configuration;
	}

	public StreamStatusMaintainer getStreamStatusMaintainer() {
		return operatorChain;
	}

	RecordWriterOutput<?>[] getStreamOutputs() {
		return operatorChain.getStreamOutputs();
	}

	// ------------------------------------------------------------------------
	//  Checkpoint and Restore
	// ------------------------------------------------------------------------

	@Override
	public Future<Boolean> triggerCheckpointAsync(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions,
		boolean advanceToEndOfEventTime) {

		CompletableFuture<Boolean> result = new CompletableFuture<>();

		/*************************************************
		 * 
		 *  注释： 提交一个 Mail 到 mainMailboxExecutor 中运行
		 *  待执行的 checkpoint 被封装成为 Mail 提交给 mainMailboxExecutor 来执行
		 *  -
		 *  TaskManager 接收到 JobMaster 的 TriggerCheckpoint 消息后，
		 *  经过层层调用最后使用 AbstractInvokable 的 triggerCheckpointAsync 方法来处理。
		 *  AbstractInvokable 是对在 TaskManager 中可执行任务的抽象。
		 *  triggerCheckpointAsync 的具体实现在 AbstractInvokable 的子类 StreamTask 中，
		 *  其核心逻辑就是使用线程池异步调用 triggerCheckpoint 方法。
		 */
		mainMailboxExecutor.execute(() -> {
			latestAsyncCheckpointStartDelayNanos = 1_000_000 * Math.max(0, System.currentTimeMillis() - checkpointMetaData.getTimestamp());
			try {

				/*************************************************
				 * 
				 *  注释： 执行 Checkpoint
				 */
				result.complete(triggerCheckpoint(checkpointMetaData, checkpointOptions, advanceToEndOfEventTime));

			} catch(Exception ex) {
				// Report the failure both via the Future result but also to the mailbox
				result.completeExceptionally(ex);
				throw ex;
			}
		}, "checkpoint %s with %s", checkpointMetaData, checkpointOptions);
		return result;
	}

	private boolean triggerCheckpoint(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions,
		boolean advanceToEndOfEventTime) throws Exception {
		try {
			// TODO_MA 注释： 如果我们注入检查点，则无法对齐
			// No alignment if we inject a checkpoint
			CheckpointMetrics checkpointMetrics = new CheckpointMetrics().setAlignmentDurationNanos(0L);

			/*************************************************
			 * 
			 *  注释： 执行 Checkpoint 的初始化
			 */
			subtaskCheckpointCoordinator.initCheckpoint(checkpointMetaData.getCheckpointId(), checkpointOptions);

			/*************************************************
			 * 
			 *  注释： 执行 Checkpoint 的执行， 主要做两件事情：
			 *  1、创建Checkpoint Barrier并向下游节点广播
			 *  2、触发本节点的快照操作
			 */
			boolean success = performCheckpoint(checkpointMetaData, checkpointOptions, checkpointMetrics, advanceToEndOfEventTime);

			// TODO_MA 注释： 如果未成功，则取消本次 checkpoint
			if(!success) {
				declineCheckpoint(checkpointMetaData.getCheckpointId());
			}
			return success;
		} catch(Exception e) {
			// propagate exceptions only if the task is still in "running" state
			if(isRunning) {
				throw new Exception("Could not perform checkpoint " + checkpointMetaData.getCheckpointId() + " for operator " + getName() + '.',
					e);
			} else {
				LOG.debug("Could not perform checkpoint {} for operator {} while the " + "invokable was not in state running.",
					checkpointMetaData.getCheckpointId(), getName(), e);
				return false;
			}
		}
	}

	@Override
	public <E extends Exception> void executeInTaskThread(ThrowingRunnable<E> runnable, String descriptionFormat,
		Object... descriptionArgs) throws E {
		if(mailboxProcessor.isMailboxThread()) {
			runnable.run();
		} else {
			mainMailboxExecutor.execute(runnable, descriptionFormat, descriptionArgs);
		}
	}

	@Override
	public void triggerCheckpointOnBarrier(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions,
		CheckpointMetrics checkpointMetrics) throws IOException {

		try {

			/*************************************************
			 * 
			 *  注释： 调用 performCheckpoint() 执行 checkpoint
			 */
			if(performCheckpoint(checkpointMetaData, checkpointOptions, checkpointMetrics, false)) {
				if(isSynchronousSavepointId(checkpointMetaData.getCheckpointId())) {
					runSynchronousSavepointMailboxLoop();
				}
			}
		} catch(CancelTaskException e) {
			LOG.info("Operator {} was cancelled while performing checkpoint {}.", getName(), checkpointMetaData.getCheckpointId());
			throw e;
		} catch(Exception e) {
			throw new IOException("Could not perform checkpoint " + checkpointMetaData.getCheckpointId() + " for operator " + getName() + '.',
				e);
		}
	}

	@Override
	public void abortCheckpointOnBarrier(long checkpointId, Throwable cause) throws IOException {
		subtaskCheckpointCoordinator.abortCheckpointOnBarrier(checkpointId, cause, operatorChain);
	}

	private boolean performCheckpoint(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions,
		CheckpointMetrics checkpointMetrics, boolean advanceToEndOfTime) throws Exception {

		LOG.debug("Starting checkpoint ({}) {} on task {}", checkpointMetaData.getCheckpointId(), checkpointOptions.getCheckpointType(),
			getName());

		/*************************************************
		 * 
		 *  注释： 如果是正在运行中，则进行 Checkpoint
		 */
		if(isRunning) {
			actionExecutor.runThrowing(() -> {

				if(checkpointOptions.getCheckpointType().isSynchronous()) {
					setSynchronousSavepointId(checkpointMetaData.getCheckpointId());

					/*************************************************
					 * 
					 *  注释： 当作业处于 TERMINATED 状态时，SourceStreamTask 会向下游
					 *  发送 MAX_WATERMARK，触发所有的 timer，使得相关的 state 数据（例如 window state）能够刷盘
					 */
					if(advanceToEndOfTime) {

						// TODO_MA 注释： 进入到 SourceStreamTask
						advanceToEndOfEventTime();
					}
				}

				/*************************************************
				 * 
				 *  注释： 执行 State 的 Checkpoint
				 */
				subtaskCheckpointCoordinator
					.checkpointState(checkpointMetaData, checkpointOptions, checkpointMetrics, operatorChain, this::isCanceled);
			});

			return true;
		} else {

			/*************************************************
			 * 
			 *  注释： Task 已经不是 RUNNING 状态了，向下游 Task 广播 CancelCheckpointMarker，下游算子会执行 abort。
			 *  然后  CheckpointCoordinator 发送 declineCheckpoint 消息
			 */
			actionExecutor.runThrowing(() -> {
				// we cannot perform our checkpoint - let the downstream operators know that they
				// should not wait for any input from this operator
				// we cannot broadcast the cancellation markers on the 'operator chain', because it may not yet be created
				final CancelCheckpointMarker message = new CancelCheckpointMarker(checkpointMetaData.getCheckpointId());
				recordWriter.broadcastEvent(message);
			});

			return false;
		}
	}

	protected void declineCheckpoint(long checkpointId) {
		getEnvironment().declineCheckpoint(checkpointId,
			new CheckpointException("Task Name" + getName(), CheckpointFailureReason.CHECKPOINT_DECLINED_TASK_NOT_READY));
	}

	public final ExecutorService getAsyncOperationsThreadPool() {
		return asyncOperationsThreadPool;
	}

	@Override
	public Future<Void> notifyCheckpointCompleteAsync(long checkpointId) {

		/*************************************************
		 * 
		 *  注释：
		 */
		return notifyCheckpointOperation(() -> notifyCheckpointComplete(checkpointId), String.format("checkpoint %d complete", checkpointId));
	}

	@Override
	public Future<Void> notifyCheckpointAbortAsync(long checkpointId) {
		return notifyCheckpointOperation(
			() -> subtaskCheckpointCoordinator.notifyCheckpointAborted(checkpointId, operatorChain, this::isRunning),
			String.format("checkpoint %d aborted", checkpointId));
	}

	private Future<Void> notifyCheckpointOperation(RunnableWithException runnable, String description) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		mailboxProcessor.getMailboxExecutor(TaskMailbox.MAX_PRIORITY).execute(() -> {
			try {
				runnable.run();
			} catch(Exception ex) {
				result.completeExceptionally(ex);
				throw ex;
			}
			result.complete(null);
		}, description);
		return result;
	}

	private void notifyCheckpointComplete(long checkpointId) throws Exception {

		/*************************************************
		 * 
		 *  注释：
		 */
		subtaskCheckpointCoordinator.notifyCheckpointComplete(checkpointId, operatorChain, this::isRunning);
		if(isRunning && isSynchronousSavepointId(checkpointId)) {
			finishTask();
			// Reset to "notify" the internal synchronous savepoint mailbox loop.
			resetSynchronousSavepointId();
		}
	}

	private void tryShutdownTimerService() {

		if(!timerService.isTerminated()) {

			try {
				final long timeoutMs = getEnvironment().getTaskManagerInfo().getConfiguration().
					getLong(TaskManagerOptions.TASK_CANCELLATION_TIMEOUT_TIMERS);

				if(!timerService.shutdownServiceUninterruptible(timeoutMs)) {
					LOG.warn(
						"Timer service shutdown exceeded time limit of {} ms while waiting for pending " + "timers. Will continue with shutdown procedure.",
						timeoutMs);
				}
			} catch(Throwable t) {
				// catch and log the exception to not replace the original exception
				LOG.error("Could not shut down timer service", t);
			}
		}
	}

	// ------------------------------------------------------------------------
	//  Operator Events
	// ------------------------------------------------------------------------

	@Override
	public void dispatchOperatorEvent(OperatorID operator, SerializedValue<OperatorEvent> event) throws FlinkException {
		try {
			mainMailboxExecutor.execute(() -> {
				try {
					operatorChain.dispatchOperatorEvent(operator, event);
				} catch(Throwable t) {
					mailboxProcessor.reportThrowable(t);
				}
			}, "dispatch operator event");
		} catch(RejectedExecutionException e) {
			// this happens during shutdown, we can swallow this
		}
	}

	// ------------------------------------------------------------------------
	//  State backend
	// ------------------------------------------------------------------------

	private StateBackend createStateBackend() throws Exception {

		// TODO_MA 注释： 获取配置中的 StateBackend
		final StateBackend fromApplication = configuration.getStateBackend(getUserCodeClassLoader());

		/*************************************************
		 * 
		 *  注释： 根据配置获取 StateBackend
		 *  一般情况下，我们在生产环境中，会去进行配置，在 flink-conf.yaml 文件中进行配置：
		 *  1、state.backend: filesystem
		 *  2、state.checkpoints.dir: hdfs://namenode:40010/flink/checkpoints
		 *  一般有三种方式：
		 *  1、state.backend: filesystem = FsStateBackend
		 *  2、state.backend: jobmanager = MemoryStateBackend
		 *  3、state.backend: rocksdb = RocksDBStateBackend
		 *  -
		 *  也可以在程序中，进行设置：
		 *  StreamExecutionEnvironment.setStateBackend(StateBackend backend) 这种方式会覆盖配置文件中的配置
		 */
		return StateBackendLoader.fromApplicationOrConfigOrDefault(fromApplication, getEnvironment().getTaskManagerInfo().getConfiguration(),
			getUserCodeClassLoader(), LOG);
	}

	/**
	 * Returns the {@link TimerService} responsible for telling the current processing time and registering actual timers.
	 */
	@VisibleForTesting
	TimerService getTimerService() {
		return timerService;
	}

	@VisibleForTesting
	OP getHeadOperator() {
		return this.headOperator;
	}

	@VisibleForTesting
	StreamTaskActionExecutor getActionExecutor() {
		return actionExecutor;
	}

	public ProcessingTimeServiceFactory getProcessingTimeServiceFactory() {
		return mailboxExecutor -> new ProcessingTimeServiceImpl(timerService, callback -> deferCallbackToMailbox(mailboxExecutor, callback));
	}

	/**
	 * Handles an exception thrown by another thread (e.g. a TriggerTask),
	 * other than the one executing the main task by failing the task entirely.
	 *
	 * <p>In more detail, it marks task execution failed for an external reason
	 * (a reason other than the task code itself throwing an exception). If the task
	 * is already in a terminal state (such as FINISHED, CANCELED, FAILED), or if the
	 * task is already canceling this does nothing. Otherwise it sets the state to
	 * FAILED, and, if the invokable code is running, starts an asynchronous thread
	 * that aborts that code.
	 *
	 * <p>This method never blocks.
	 */
	@Override
	public void handleAsyncException(String message, Throwable exception) {
		if(isRunning) {
			// only fail if the task is still running
			asyncExceptionHandler.handleAsyncException(message, exception);
		}
	}

	// ------------------------------------------------------------------------
	//  Utilities
	// ------------------------------------------------------------------------

	@Override
	public String toString() {
		return getName();
	}

	// ------------------------------------------------------------------------

	/**
	 * Utility class to encapsulate the handling of asynchronous exceptions.
	 */
	static class StreamTaskAsyncExceptionHandler {
		private final Environment environment;

		StreamTaskAsyncExceptionHandler(Environment environment) {
			this.environment = environment;
		}

		void handleAsyncException(String message, Throwable exception) {
			environment.failExternally(new AsynchronousException(message, exception));
		}
	}

	public final CloseableRegistry getCancelables() {
		return cancelables;
	}

	// ------------------------------------------------------------------------

	@VisibleForTesting
	public static <OUT> RecordWriterDelegate<SerializationDelegate<StreamRecord<OUT>>> createRecordWriterDelegate(StreamConfig configuration,
		Environment environment) {

		/*************************************************
		 * 
		 *  注释： 创建 RecordWriter
		 */
		List<RecordWriter<SerializationDelegate<StreamRecord<OUT>>>> recordWrites = createRecordWriters(configuration, environment);

		if(recordWrites.size() == 1) {
			return new SingleRecordWriter<>(recordWrites.get(0));
		} else if(recordWrites.size() == 0) {
			return new NonRecordWriter<>();
		} else {
			return new MultipleRecordWriters<>(recordWrites);
		}
	}

	private static <OUT> List<RecordWriter<SerializationDelegate<StreamRecord<OUT>>>> createRecordWriters(StreamConfig configuration,
		Environment environment) {

		// TODO_MA 注释： 初始化一个 ArrayList 容器用来存放创建出来的 RecordWriter
		List<RecordWriter<SerializationDelegate<StreamRecord<OUT>>>> recordWriters = new ArrayList<>();

		// TODO_MA 注释： 获取该 StreamTask 的输出 StreamEdge 集合
		List<StreamEdge> outEdgesInOrder = configuration.getOutEdgesInOrder(environment.getUserClassLoader());

		/*************************************************
		 * 
		 *  注释： 按照 out StreamEdge 的个数来构建多个 RecordWriter，不过一般就是一个
		 */
		for(int i = 0; i < outEdgesInOrder.size(); i++) {
			StreamEdge edge = outEdgesInOrder.get(i);

			// TODO_MA 注释： 一个 out StreamEdge 来构建 一个 RecordWriter
			// TODO_MA 注释： 大概率 createRecordWriter() 方法的返回值是： ChannelSelectorRecordWriter
			recordWriters.add(createRecordWriter(edge, i, environment, environment.getTaskInfo().getTaskName(), edge.getBufferTimeout()));
		}
		return recordWriters;
	}

	@SuppressWarnings("unchecked")
	private static <OUT> RecordWriter<SerializationDelegate<StreamRecord<OUT>>> createRecordWriter(StreamEdge edge, int outputIndex,
		Environment environment, String taskName, long bufferTimeout) {

		/*************************************************
		 * 
		 *  注释： 获取流分区器
		 *  1、如果上游 StreamNode 和 下游 StreamNode 的并行度一样，则使用： ForwardPartitioner 数据分发策略
		 * 	2、如果上游 StreamNode 和 下游 StreamNode 的并行度不一样，则使用： RebalancePartitioner 数据分发策略
		 */
		StreamPartitioner<OUT> outputPartitioner = null;
		// Clones the partition to avoid multiple stream edges sharing the same stream partitioner,
		// like the case of https://issues.apache.org/jira/browse/FLINK-14087.
		try {
			outputPartitioner = InstantiationUtil.clone((StreamPartitioner<OUT>) edge.getPartitioner(), environment.getUserClassLoader());
		} catch(Exception e) {
			ExceptionUtils.rethrow(e);
		}

		LOG.debug("Using partitioner {} for output {} of task {}", outputPartitioner, outputIndex, taskName);

		/*************************************************
		 * 
		 *  注释： 获取该 ResultPartitionWriter
		 *  具体实现：ConsumableNotifyingResultPartitionWriterDecorator
		 */
		ResultPartitionWriter bufferWriter = environment.getWriter(outputIndex);

		// TODO_MA 注释： 我们在这里用键组的数量（也就是最大并行度）初始化分区程序
		// we initialize the partitioner here with the number of key groups (aka max. parallelism)
		if(outputPartitioner instanceof ConfigurableStreamPartitioner) {
			int numKeyGroups = bufferWriter.getNumTargetKeyGroups();
			if(0 < numKeyGroups) {
				((ConfigurableStreamPartitioner) outputPartitioner).configure(numKeyGroups);
			}
		}

		// TODO_MA 注释： 其实这个 output 就是负责帮您完成这个 StrewamTask 的所有数据的输出
		// TODO_MA 注释： 输出到 ResultPartition
		// TODO_MA 注释： 初始化输出 ChannelSelectorRecordWriter
		RecordWriter<SerializationDelegate<StreamRecord<OUT>>> output = new RecordWriterBuilder<SerializationDelegate<StreamRecord<OUT>>>()
			.setChannelSelector(outputPartitioner).setTimeout(bufferTimeout).setTaskName(taskName)
			// TODO_MA 注释： 构建一个 RecordWriter 返回
			.build(bufferWriter);

		output.setMetricGroup(environment.getMetricGroup().getIOMetricGroup());
		return output;
	}

	private void handleTimerException(Exception ex) {
		handleAsyncException("Caught exception while processing timer.", new TimerException(ex));
	}

	@VisibleForTesting
	ProcessingTimeCallback deferCallbackToMailbox(MailboxExecutor mailboxExecutor, ProcessingTimeCallback callback) {
		return timestamp -> {
			mailboxExecutor.execute(() -> invokeProcessingTimeCallback(callback, timestamp), "Timer callback for %s @ %d", callback, timestamp);
		};
	}

	private void invokeProcessingTimeCallback(ProcessingTimeCallback callback, long timestamp) {
		try {
			callback.onProcessingTime(timestamp);
		} catch(Throwable t) {
			handleAsyncException("Caught exception while processing timer.", new TimerException(t));
		}
	}

	protected long getAsyncCheckpointStartDelayNanos() {
		return latestAsyncCheckpointStartDelayNanos;
	}
}
