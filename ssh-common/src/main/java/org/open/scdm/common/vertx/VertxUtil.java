package org.open.scdm.common.vertx;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class VertxUtil {
	/**
	 * vertx
	 */
	private final Vertx vertx;
	// 双重检查锁定必须配合 volatile，否则可能读到未初始化完成的实例
	private static volatile VertxUtil VERTX_UTIL;
	/**
	 * 阻塞任务执行器。承载所有 MINA SSHD 阻塞调用（建立 SSH 会话、认证、开通 direct-tcpip 通道）。
	 * <p>
	 * 原实现为 ThreadPoolExecutor(2,4)，最多 4 个阻塞任务并行，第 5 个连接请求必须排队等待
	 * 通道建立，是高并发下的硬性吞吐上限。改为「每任务一虚拟线程」后，
	 * 成千上万条连接可同时建立各自的 SSH 通道，阻塞在 IO 上的虚拟线程几乎不占资源。
	 * <p>
	 * 注意：SSH 库内部大量使用 synchronized，但 JDK 24+(JEP 491) 起虚拟线程在 synchronized
	 * 中阻塞不再 pin 载体线程，因此本项目(JDK 25)可安全地用虚拟线程承载 SSH 阻塞调用。
	 */
	private final ExecutorService blockingExecutor;

	private VertxUtil() {
		blockingExecutor = Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("ssh-blocking-", 0).factory());
		VertxOptions options = new VertxOptions();
		// event loop 是 SOCKS5/端口转发吞吐的核心：原值 1 会把所有连接的读写串行化在单线程上。
		// 阻塞写已在 ChannelUtils 中 offload 到虚拟线程，event loop 只做非阻塞读写，按核数配置即可。
		int cores = Runtime.getRuntime().availableProcessors();
		options.setEventLoopPoolSize(Math.max(2, cores));
		options.setWorkerPoolSize(Math.max(2, cores));
		vertx = Vertx.vertx(options);
	}

	public void pushTask(Runnable runnable) {
		blockingExecutor.execute(runnable);
	}

	public void pushTask(Runnable runnable, long time) {
		vertx.setTimer(time, (v) -> pushTask(runnable));
	}

	public Vertx getVertx() {
		return vertx;
	}

	/**
	 * 暴露阻塞执行器，供需要 per-connection 串行虚拟线程的场景复用同一线程工厂语义
	 */
	public ExecutorService getBlockingExecutor() {
		return blockingExecutor;
	}

	public static VertxUtil current() {
		if (VERTX_UTIL == null) {
			synchronized (VertxUtil.class) {
				if (VERTX_UTIL == null) {
					VERTX_UTIL = new VertxUtil();
				}
			}
		}
		return VERTX_UTIL;
	}

}
