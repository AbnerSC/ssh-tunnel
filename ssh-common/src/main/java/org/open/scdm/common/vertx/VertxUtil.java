package org.open.scdm.common.vertx;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class VertxUtil {
	/**
	 * vertx
	 */
	private final Vertx vertx;
	/**
	 * 阻塞任务队列
	 */
	// 双重检查锁定必须配合 volatile，否则可能读到未初始化完成的实例
	private static volatile VertxUtil VERTX_UTIL;
	private final ThreadPoolExecutor poolExecutor;

	private VertxUtil() {
		poolExecutor = new ThreadPoolExecutor(2, 4, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		VertxOptions options = new VertxOptions();
		options.setWorkerPoolSize(1);
		options.setEventLoopPoolSize(1);
		vertx = Vertx.vertx(options);
	}

	public void pushTask(Runnable runnable) {
		poolExecutor.execute(runnable);
	}

	public void pushTask(Runnable runnable, long time) {
		vertx.setTimer(time, (v) -> pushTask(runnable));
	}

	public Vertx getVertx() {
		return vertx;
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
