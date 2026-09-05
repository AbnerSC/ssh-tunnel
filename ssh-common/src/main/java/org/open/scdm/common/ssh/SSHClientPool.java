package org.open.scdm.common.ssh;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.open.scdm.common.config.CopyItem;
import org.open.scdm.common.config.SSHConfig;
import org.open.scdm.common.dispatcher.ABSDispatcher;
import org.open.scdm.common.vertx.VertxUtil;
import com.jcraft.jsch.Session;

public class SSHClientPool extends ABSDispatcher {
	/**
	 * 客户端实现
	 */
	private SSHClientImpl[] clientImpls;
	/**
	 * 轮询指针：原为 int + synchronized，高并发下每次取会话都抢全局锁。
	 * 改为 AtomicInteger 无锁轮询，配合多 Session 池分散负载。
	 */
	private final AtomicInteger index = new AtomicInteger(0);
	private static final long ERROR_WAIT_TIME = 5000;
	private final int poolSize;

	public SSHClientPool(SSHConfig sshConfig) {
		super();
		// 恢复多 Session 连接池：原硬编码 poolSize=1 使所有 SOCKS5 通道复用单个 SSH Session，
		// 单条 SSH TCP 连接的加密/多路复用成为吞吐瓶颈。多 Session 可将负载分散到多条连接上。
		Integer configured = sshConfig.getPool();
		this.poolSize = (configured == null || configured < 1) ? 1 : configured;
		clientImpls = new SSHClientImpl[poolSize];
		List<CopyItem> copyItems = sshConfig.getLocals();
		PortForwardServer[] forwardServers = new PortForwardServer[copyItems.size()];
		for (int i = 0; i < forwardServers.length; i++) {
			forwardServers[i] = new PortForwardServer(copyItems.get(i));
		}
		for (int i = 0; i < clientImpls.length; i++) {
			clientImpls[i] = new SSHClientImpl(sshConfig, this::electMain, forwardServers);
		}
	}

	long lastTime = 0;

	@Override
	protected boolean run() {
		long time = System.currentTimeMillis();
		if ((time - lastTime) < 2000) {
			return true;
		}
		lastTime = time;
		// 检查是否链接
		SSHStatusEnum sshStatus;
		for (SSHClientImpl clientImpl : clientImpls) {
			sshStatus = clientImpl.sshStatus;
			switch (sshStatus) {
			case AWAIT_CONNECT:
				// 连接
				if (time > clientImpl.getNextRunTime()) {
					// 直接置客户端状态为 CONNECTING：原写法只改了局部变量 sshStatus，
					// clientImpl 仍是 AWAIT_CONNECT，异步 openSession 未及时执行时会被重复调度。
					clientImpl.sshStatus = SSHStatusEnum.CONNECTING;
					pushSpecialTask(clientImpl, () -> clientImpl.openSession());
				}
				break;
			case CONNECTED:
				// 维持心跳
				pushSpecialTask(clientImpl, () -> clientImpl.sendKeepAliveMsg());
				break;
			default:
				break;
			}
		}
		return true;
	}

	/**
	 * 选举一个链接出来做主链接
	 */
	private void electMain() {
		synchronized (this) {
			for (SSHClientImpl impl : clientImpls) {
				if (impl.isMain() && impl.isConnected()) {
					// 如果连接已经是主程序，并且已经打开了，那就算了
					return;
				}
			}
			for (SSHClientImpl impl : clientImpls) {
				if (impl.isConnected()) {
					impl.bandMainSession();
					return;
				}
			}
		}
	}

	public Session trySession() {
		int size = poolSize;
		// 无锁轮询：从上次位置的下一个开始，找到一个已连接的 Session。
		// floorMod 保证 getAndIncrement 溢出为负数时仍能得到合法下标。
		for (int i = 0; i < size; i++) {
			int idx = Math.floorMod(index.getAndIncrement(), size);
			SSHClientImpl clientImpl = clientImpls[idx];
			if (clientImpl.isConnected()) {
				return clientImpl.getSession();
			}
		}
		return null;
	}

//存入一些可能报错的任务
	private void pushSpecialTask(SSHClientImpl clientImpl, SpecialTask task) {
		VertxUtil.current().pushTask(() -> {
			try {
				task.run();
			} catch (Exception e) {
				if (Logf.isLog()) {
					Logf.log("发生运行时异常: %s", e.getMessage() == null ? e.toString() : e.getMessage());
					e.printStackTrace();
				}
				clientImpl.setNextRunTime(System.currentTimeMillis() + ERROR_WAIT_TIME);
				clientImpl.disconnectSession();
				electMain();
			}
		});
	}

	static interface SpecialTask {
		public void run() throws Exception;
	}

}
