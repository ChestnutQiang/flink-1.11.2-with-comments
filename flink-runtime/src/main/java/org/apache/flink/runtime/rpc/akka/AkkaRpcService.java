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

package org.apache.flink.runtime.rpc.akka;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.concurrent.akka.ActorSystemScheduledExecutorAdapter;
import org.apache.flink.runtime.rpc.FencedMainThreadExecutable;
import org.apache.flink.runtime.rpc.FencedRpcEndpoint;
import org.apache.flink.runtime.rpc.FencedRpcGateway;
import org.apache.flink.runtime.rpc.RpcEndpoint;
import org.apache.flink.runtime.rpc.RpcGateway;
import org.apache.flink.runtime.rpc.RpcServer;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.RpcUtils;
import org.apache.flink.runtime.rpc.akka.exceptions.AkkaRpcRuntimeException;
import org.apache.flink.runtime.rpc.exceptions.RpcConnectionException;
import org.apache.flink.runtime.rpc.messages.HandshakeSuccessMessage;
import org.apache.flink.runtime.rpc.messages.RemoteHandshakeMessage;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.util.AutoCloseableAsync;
import org.apache.flink.util.ExecutorUtils;
import org.apache.flink.util.TimeUtils;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.actor.Props;
import akka.dispatch.Futures;
import akka.pattern.Patterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import scala.Option;
import scala.concurrent.Future;
import scala.reflect.ClassTag$;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * // TODO_MA 注释： 基于 Akka 的 {@link RpcService} 实现。
 * // TODO_MA 注释： RpcService 启动Akka actor，以从{@link RpcGateway}接收 RPC调用。
 * Akka based {@link RpcService} implementation.
 * The RPC service starts an Akka actor to receive RPC invocations from a {@link RpcGateway}.
 */
@ThreadSafe
public class AkkaRpcService implements RpcService {

	private static final Logger LOG = LoggerFactory.getLogger(AkkaRpcService.class);

	static final int VERSION = 2;

	private final Object lock = new Object();

	/*************************************************
	 *
	 *  注释： ActorSystem 是 AkkaRpcService 的成员变量
	 *  由此可见： AkkaRpcService 是 RpcService 的唯一实现类
	 *  AkkaRpcService 是对 ActorSystem 的封装
	 */
	private final ActorSystem actorSystem;
	private final AkkaRpcServiceConfiguration configuration;

	@GuardedBy("lock")
	private final Map<ActorRef, RpcEndpoint> actors = new HashMap<>(4);

	private final String address;
	private final int port;

	private final boolean captureAskCallstacks;

	private final ScheduledExecutor internalScheduledExecutor;

	private final CompletableFuture<Void> terminationFuture;

	private final Supervisor supervisor;

	private volatile boolean stopped;

	@VisibleForTesting
	public AkkaRpcService(final ActorSystem actorSystem, final AkkaRpcServiceConfiguration configuration) {

		// TODO_MA 注释：参数检查
		this.actorSystem = checkNotNull(actorSystem, "actor system");
		this.configuration = checkNotNull(configuration, "akka rpc service configuration");

		// TODO_MA 注释：主机名称
		Address actorSystemAddress = AkkaUtils.getAddress(actorSystem);
		if(actorSystemAddress.host().isDefined()) {
			address = actorSystemAddress.host().get();
		} else {
			address = "";
		}

		// TODO_MA 注释：端口号
		if(actorSystemAddress.port().isDefined()) {
			port = (Integer) actorSystemAddress.port().get();
		} else {
			port = -1;
		}

		captureAskCallstacks = configuration.captureAskCallStack();

		/*************************************************
		 *
		 *  注释： 生成一个： ActorSystemScheduledExecutorAdapter
		 */
		internalScheduledExecutor = new ActorSystemScheduledExecutorAdapter(actorSystem);

		terminationFuture = new CompletableFuture<>();

		stopped = false;

		/*************************************************
		 *
		 *  注释： 启动 SupervisorActor
		 */
		supervisor = startSupervisorActor();
	}

	/*************************************************
	 *
	 *  注释： Supervisor 是一个 Actor
	 *  最终总结是这样的： 我们启动一个 commonRpcServices 内部启动一个 ActorSystem， 这个 ActorySystem 启动一个 ActorSystem
	 */
	private Supervisor startSupervisorActor() {

		/**
		 *
		 *  注释：
		 */
		final ExecutorService terminationFutureExecutor = Executors
			.newSingleThreadExecutor(new ExecutorThreadFactory("AkkaRpcService-Supervisor-Termination-Future-Executor"));

		/**
		 *
		 *  注释：
		 */
		final ActorRef actorRef = SupervisorActor.startSupervisorActor(actorSystem, terminationFutureExecutor);

		/**
		 *
		 *  注释： 创建一个 SupervisorActor
		 */
		return Supervisor.create(actorRef, terminationFutureExecutor);
	}

	public ActorSystem getActorSystem() {
		return actorSystem;
	}

	protected int getVersion() {
		return VERSION;
	}

	@Override
	public String getAddress() {
		return address;
	}

	@Override
	public int getPort() {
		return port;
	}

	// this method does not mutate state and is thus thread-safe
	@Override
	public <C extends RpcGateway> CompletableFuture<C> connect(final String address, final Class<C> clazz) {

		/**
		 *
		 *  注释：
		 */
		return connectInternal(address, clazz, (ActorRef actorRef) -> {
			Tuple2<String, String> addressHostname = extractAddressHostname(actorRef);

			return new AkkaInvocationHandler(addressHostname.f0, addressHostname.f1, actorRef, configuration.getTimeout(),
				configuration.getMaximumFramesize(), null, captureAskCallstacks);
		});
	}

	// this method does not mutate state and is thus thread-safe
	@Override
	public <F extends Serializable, C extends FencedRpcGateway<F>> CompletableFuture<C> connect(String address, F fencingToken, Class<C> clazz) {

		/**
		 *
		 *  注释：
		 */
		return connectInternal(address, clazz, (ActorRef actorRef) -> {
			Tuple2<String, String> addressHostname = extractAddressHostname(actorRef);

			return new FencedAkkaInvocationHandler<>(addressHostname.f0, addressHostname.f1, actorRef, configuration.getTimeout(),
				configuration.getMaximumFramesize(), null, () -> fencingToken, captureAskCallstacks);
		});
	}

	/*************************************************
	 *
	 *  注释： 这是一个生命周期方法，在 RpcEndpoint 初始化的时候，会调用这个方法执行
	 *  根据方法的定义可以看出，当启动一个 RpcEndpoint 的时候，就会返回一个 RpcServer
	 */
	@Override
	public <C extends RpcEndpoint & RpcGateway> RpcServer startServer(C rpcEndpoint) {
		checkNotNull(rpcEndpoint, "rpc endpoint");

		// TODO_MA 注释： 注册一个 Akka Actor
		final SupervisorActor.ActorRegistration actorRegistration = registerAkkaRpcActor(rpcEndpoint);
		// TODO_MA 注释： 返回一个 ActorRef
		final ActorRef actorRef = actorRegistration.getActorRef();

		final CompletableFuture<Void> actorTerminationFuture = actorRegistration.getTerminationFuture();

		LOG.info("Starting RPC endpoint for {} at {} .", rpcEndpoint.getClass().getName(), actorRef.path());

		// TODO_MA 注释： 获取 hostname 和 port
		final String akkaAddress = AkkaUtils.getAkkaURL(actorSystem, actorRef);
		final String hostname;

		Option<String> host = actorRef.path().address().host();
		if(host.isEmpty()) {
			hostname = "localhost";
		} else {
			hostname = host.get();
		}

		Set<Class<?>> implementedRpcGateways = new HashSet<>(RpcUtils.extractImplementedRpcGateways(rpcEndpoint.getClass()));

		implementedRpcGateways.add(RpcServer.class);
		implementedRpcGateways.add(AkkaBasedEndpoint.class);

		final InvocationHandler akkaInvocationHandler;

		/*************************************************
		 *
		 *  注释： 通过代理的方式来获取一个 ActorRef 对象
		 */
		if(rpcEndpoint instanceof FencedRpcEndpoint) {
			// a FencedRpcEndpoint needs a FencedAkkaInvocationHandler
			akkaInvocationHandler = new FencedAkkaInvocationHandler<>(akkaAddress, hostname, actorRef, configuration.getTimeout(),
				configuration.getMaximumFramesize(), actorTerminationFuture, ((FencedRpcEndpoint<?>) rpcEndpoint)::getFencingToken,
				captureAskCallstacks);

			implementedRpcGateways.add(FencedMainThreadExecutable.class);
		} else {

			/*************************************************
			 *
			 *  注释： RpcServer 实现类
			 *  JDK 提供的动态代理： Proxy InvocationHandler
			 */
			akkaInvocationHandler = new AkkaInvocationHandler(akkaAddress, hostname, actorRef, configuration.getTimeout(),
				configuration.getMaximumFramesize(), actorTerminationFuture, captureAskCallstacks);
		}

		// Rather than using the System ClassLoader directly, we derive the ClassLoader
		// from this class . That works better in cases where Flink runs embedded and all Flink
		// code is loaded dynamically (for example from an OSGI bundle) through a custom ClassLoader
		ClassLoader classLoader = getClass().getClassLoader();

		/*************************************************
		 *
		 *  注释： 获取 RpcServer 对象, 启动 RpcServer
		 */
		@SuppressWarnings("unchecked") RpcServer server = (RpcServer) Proxy
			.newProxyInstance(classLoader, implementedRpcGateways.toArray(new Class<?>[implementedRpcGateways.size()]), akkaInvocationHandler);

		return server;
	}

	private <C extends RpcEndpoint & RpcGateway> SupervisorActor.ActorRegistration registerAkkaRpcActor(C rpcEndpoint) {
		final Class<? extends AbstractActor> akkaRpcActorType;

		/*************************************************
		 *
		 *  注释： 创建的 Akka Actor 具体类型
		 */
		if(rpcEndpoint instanceof FencedRpcEndpoint) {
			akkaRpcActorType = FencedAkkaRpcActor.class;
		} else {
			akkaRpcActorType = AkkaRpcActor.class;
		}

		synchronized(lock) {
			checkState(!stopped, "RpcService is stopped");

			// TODO_MA 注释： 构建一个 Akka Actor
			final SupervisorActor.StartAkkaRpcActorResponse startAkkaRpcActorResponse = SupervisorActor.startAkkaRpcActor(supervisor.getActor(),
				actorTerminationFuture -> Props
					.create(akkaRpcActorType, rpcEndpoint, actorTerminationFuture, getVersion(), configuration.getMaximumFramesize()),
				rpcEndpoint.getEndpointId());

			// TODO_MA 注释： 构建一个 Actor 注册对象
			final SupervisorActor.ActorRegistration actorRegistration = startAkkaRpcActorResponse.orElseThrow(cause -> new AkkaRpcRuntimeException(
				String.format("Could not create the %s for %s.", AkkaRpcActor.class.getSimpleName(), rpcEndpoint.getEndpointId()), cause));

			actors.put(actorRegistration.getActorRef(), rpcEndpoint);

			return actorRegistration;
		}
	}

	@Override
	public <F extends Serializable> RpcServer fenceRpcServer(RpcServer rpcServer, F fencingToken) {
		if(rpcServer instanceof AkkaBasedEndpoint) {

			/*************************************************
			 *
			 *  注释： 得到 InvocationHandler 对象
			 */
			InvocationHandler fencedInvocationHandler = new FencedAkkaInvocationHandler<>(rpcServer.getAddress(), rpcServer.getHostname(),
				((AkkaBasedEndpoint) rpcServer).getActorRef(), configuration.getTimeout(), configuration.getMaximumFramesize(), null,
				() -> fencingToken, captureAskCallstacks);

			// Rather than using the System ClassLoader directly, we derive the ClassLoader
			// from this class . That works better in cases where Flink runs embedded and all Flink
			// code is loaded dynamically (for example from an OSGI bundle) through a custom ClassLoader
			ClassLoader classLoader = getClass().getClassLoader();

			/*************************************************
			 *
			 *  注释： 构建代理对象返回
			 */
			return (RpcServer) Proxy.newProxyInstance(classLoader, new Class<?>[]{RpcServer.class, AkkaBasedEndpoint.class}, fencedInvocationHandler);
		} else {
			throw new RuntimeException("The given RpcServer must implement the AkkaGateway in order to fence it.");
		}
	}

	@Override
	public void stopServer(RpcServer selfGateway) {
		if(selfGateway instanceof AkkaBasedEndpoint) {
			final AkkaBasedEndpoint akkaClient = (AkkaBasedEndpoint) selfGateway;
			final RpcEndpoint rpcEndpoint;

			synchronized(lock) {
				if(stopped) {
					return;
				} else {
					rpcEndpoint = actors.remove(akkaClient.getActorRef());
				}
			}

			if(rpcEndpoint != null) {
				terminateAkkaRpcActor(akkaClient.getActorRef(), rpcEndpoint);
			} else {
				LOG.debug("RPC endpoint {} already stopped or from different RPC service", selfGateway.getAddress());
			}
		}
	}

	@Override
	public CompletableFuture<Void> stopService() {
		final CompletableFuture<Void> akkaRpcActorsTerminationFuture;

		synchronized(lock) {
			if(stopped) {
				return terminationFuture;
			}

			LOG.info("Stopping Akka RPC service.");

			stopped = true;

			akkaRpcActorsTerminationFuture = terminateAkkaRpcActors();
		}

		final CompletableFuture<Void> supervisorTerminationFuture = FutureUtils
			.composeAfterwards(akkaRpcActorsTerminationFuture, supervisor::closeAsync);

		final CompletableFuture<Void> actorSystemTerminationFuture = FutureUtils
			.composeAfterwards(supervisorTerminationFuture, () -> FutureUtils.toJava(actorSystem.terminate()));

		actorSystemTerminationFuture.whenComplete((Void ignored, Throwable throwable) -> {
			if(throwable != null) {
				terminationFuture.completeExceptionally(throwable);
			} else {
				terminationFuture.complete(null);
			}

			LOG.info("Stopped Akka RPC service.");
		});

		return terminationFuture;
	}

	@GuardedBy("lock")
	@Nonnull
	private CompletableFuture<Void> terminateAkkaRpcActors() {
		final Collection<CompletableFuture<Void>> akkaRpcActorTerminationFutures = new ArrayList<>(actors.size());

		for(Map.Entry<ActorRef, RpcEndpoint> actorRefRpcEndpointEntry : actors.entrySet()) {
			akkaRpcActorTerminationFutures.add(terminateAkkaRpcActor(actorRefRpcEndpointEntry.getKey(), actorRefRpcEndpointEntry.getValue()));
		}
		actors.clear();

		return FutureUtils.waitForAll(akkaRpcActorTerminationFutures);
	}

	private CompletableFuture<Void> terminateAkkaRpcActor(ActorRef akkaRpcActorRef, RpcEndpoint rpcEndpoint) {
		akkaRpcActorRef.tell(ControlMessages.TERMINATE, ActorRef.noSender());

		return rpcEndpoint.getTerminationFuture();
	}

	@Override
	public CompletableFuture<Void> getTerminationFuture() {
		return terminationFuture;
	}

	@Override
	public Executor getExecutor() {
		return actorSystem.dispatcher();
	}

	@Override
	public ScheduledExecutor getScheduledExecutor() {
		return internalScheduledExecutor;
	}

	@Override
	public ScheduledFuture<?> scheduleRunnable(Runnable runnable, long delay, TimeUnit unit) {
		checkNotNull(runnable, "runnable");
		checkNotNull(unit, "unit");
		checkArgument(delay >= 0L, "delay must be zero or larger");

		return internalScheduledExecutor.schedule(runnable, delay, unit);
	}

	@Override
	public void execute(Runnable runnable) {
		actorSystem.dispatcher().execute(runnable);
	}

	@Override
	public <T> CompletableFuture<T> execute(Callable<T> callable) {
		Future<T> scalaFuture = Futures.<T>future(callable, actorSystem.dispatcher());

		return FutureUtils.toJava(scalaFuture);
	}

	// ---------------------------------------------------------------------------------------
	// Private helper methods
	// ---------------------------------------------------------------------------------------

	private Tuple2<String, String> extractAddressHostname(ActorRef actorRef) {
		final String actorAddress = AkkaUtils.getAkkaURL(actorSystem, actorRef);
		final String hostname;
		Option<String> host = actorRef.path().address().host();
		if(host.isEmpty()) {
			hostname = "localhost";
		} else {
			hostname = host.get();
		}

		return Tuple2.of(actorAddress, hostname);
	}

	private <C extends RpcGateway> CompletableFuture<C> connectInternal(final String address, final Class<C> clazz,
		Function<ActorRef, InvocationHandler> invocationHandlerFactory) {
		checkState(!stopped, "RpcService is stopped");

		LOG.debug("Try to connect to remote RPC endpoint with address {}. Returning a {} gateway.", address, clazz.getName());

		final CompletableFuture<ActorRef> actorRefFuture = resolveActorAddress(address);

		final CompletableFuture<HandshakeSuccessMessage> handshakeFuture = actorRefFuture.thenCompose((ActorRef actorRef) -> FutureUtils.toJava(
			Patterns.ask(actorRef, new RemoteHandshakeMessage(clazz, getVersion()),
				configuration.getTimeout().toMilliseconds()).<HandshakeSuccessMessage>mapTo(
				ClassTag$.MODULE$.<HandshakeSuccessMessage>apply(HandshakeSuccessMessage.class))));

		return actorRefFuture.thenCombineAsync(handshakeFuture, (ActorRef actorRef, HandshakeSuccessMessage ignored) -> {

			// TODO_MA 注释： InvocationHandler = FencedAkkaInvocationHandler
			InvocationHandler invocationHandler = invocationHandlerFactory.apply(actorRef);

			// Rather than using the System ClassLoader directly, we derive the ClassLoader
			// from this class . That works better in cases where Flink runs embedded and all Flink
			// code is loaded dynamically (for example from an OSGI bundle) through a custom ClassLoader
			ClassLoader classLoader = getClass().getClassLoader();

			@SuppressWarnings("unchecked") C proxy = (C) Proxy.newProxyInstance(classLoader, new Class<?>[]{clazz}, invocationHandler);

			return proxy;
		}, actorSystem.dispatcher());
	}

	private CompletableFuture<ActorRef> resolveActorAddress(String address) {
		final ActorSelection actorSel = actorSystem.actorSelection(address);

		return actorSel.resolveOne(TimeUtils.toDuration(configuration.getTimeout())).toCompletableFuture().exceptionally(error -> {
			throw new CompletionException(
				new RpcConnectionException(String.format("Could not connect to rpc endpoint under address %s.", address), error));
		});
	}

	// ---------------------------------------------------------------------------------------
	// Private inner classes
	// ---------------------------------------------------------------------------------------

	private static final class Supervisor implements AutoCloseableAsync {

		private final ActorRef actor;

		private final ExecutorService terminationFutureExecutor;

		private Supervisor(ActorRef actor, ExecutorService terminationFutureExecutor) {
			this.actor = actor;
			this.terminationFutureExecutor = terminationFutureExecutor;
		}

		private static Supervisor create(ActorRef actorRef, ExecutorService terminationFutureExecutor) {

			/*************************************************
			 *
			 *  注释：
			 */
			return new Supervisor(actorRef, terminationFutureExecutor);
		}

		public ActorRef getActor() {
			return actor;
		}

		@Override
		public CompletableFuture<Void> closeAsync() {
			return ExecutorUtils.nonBlockingShutdown(30L, TimeUnit.SECONDS, terminationFutureExecutor);
		}
	}
}
