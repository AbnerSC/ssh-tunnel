package org.open.scdm.common.dispatcher;

import java.util.concurrent.atomic.AtomicBoolean;

import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.vertx.VertxUtil;

public abstract class ABSDispatcher {
	/**
	 * 是否关闭
	 */
	protected AtomicBoolean closed = new AtomicBoolean(true);

	protected abstract boolean run();

	public void start() {
		closed.set(false);
		VertxUtil.current().pushTask(this::runDispatcher);
	}

	/**
	 * 检查调度
	 */
	protected void runDispatcher() {
		boolean again;
		try {
			again = run();
		} catch (Throwable e) {
			// 捕获 Throwable 而非 Exception：OOM 等 Error 一旦逃逸会终止调度链，
			// SSH 会话健康检查/重连从此停摆，表现为长时间运行后“无法代理”且无法自愈
			Logf.printf("调度任务发生异常:%s", e.getMessage() == null ? e.toString() : e.getMessage());
			again = true;
		}
		// 异常后必须继续注册下一轮调度（原实现在 catch 后直接返回，调度线程永久死亡）
		if (again) {
			VertxUtil.current().pushTask(this::runDispatcher, 500);
		}
	}

	
}
