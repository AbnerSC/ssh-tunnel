package priv.dm.common.ssh;

import java.util.List;

import priv.dm.common.config.CopyItem;
import priv.dm.common.config.SSHConfig;
import priv.dm.common.dispatcher.ABSDispatcher;
import priv.dm.common.vertx.VertxUtil;
import com.jcraft.jsch.Session;

public class SSHClientPool extends ABSDispatcher {
	/**
	 * 客户端实现
	 */
	private SSHClientImpl[] clientImpls;
	/**
	 * 指针
	 */
	private int index = 0;
	private static final long ERROR_WAIT_TIME = 5000;
	private final int poolSize;

	public SSHClientPool(SSHConfig sshConfig) {
		super();
//		this.poolSize = sshConfig.getPool();
		this.poolSize = 1;
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
					sshStatus = SSHStatusEnum.CONNECTING;
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
		SSHClientImpl clientImpl;
		synchronized (this) {
			for (int i = 0; i < poolSize; i++) {
				// 找会话
				if (index == poolSize) {
					index = 0;
				}
				clientImpl = clientImpls[index++];
				if (clientImpl.isConnected()) {
					return clientImpl.getSession();
				}
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
					Logf.log("%s", "发生运行时异常");
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
