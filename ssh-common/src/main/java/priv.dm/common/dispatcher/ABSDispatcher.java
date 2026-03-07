package priv.dm.common.dispatcher;

import java.util.concurrent.atomic.AtomicBoolean;

import priv.dm.common.vertx.VertxUtil;

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
		try {
			if (run()) {
				VertxUtil.current().pushTask(this::runDispatcher, 500);
			}
		} catch (Throwable e) {
			System.out.println("调度任务发生异常" + e.getMessage());
		}
	}

	
}
