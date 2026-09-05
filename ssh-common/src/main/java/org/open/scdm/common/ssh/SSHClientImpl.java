package org.open.scdm.common.ssh;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import lombok.Setter;
import org.open.scdm.common.config.CopyItem;
import org.open.scdm.common.config.SSHConfig;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

public class SSHClientImpl {
	/**
	 * 配置
	 */
	private final SSHConfig sshConfig;
	/**
	 * ssh工具
	 */
	private static final JSch jsch = new JSch();
	/**
	 * 会话连接超时(ms)：TCP + SSH 握手阶段上限，避免 connect() 无限阻塞虚拟线程
	 */
	private static final int CONNECT_TIMEOUT = 10000;
	/**
	 * SSH 层心跳间隔(ms)：由 JSch 会话线程自动发送 keepalive 保活并探测失效，
	 * 取代原先“开 shell 通道跑命令”的脆弱探活方式
	 */
	private static final int SERVER_ALIVE_INTERVAL = 15000;
	/**
	 * 连续多少次心跳无响应即判定会话失效
	 */
	private static final int SERVER_ALIVE_COUNT_MAX = 3;
	/**
	 * 待连接。volatile：调度线程(虚拟线程)写、trySession/getSession(event loop)读，
	 * 需保证跨线程可见性，否则 event loop 可能读到过期状态。
	 */
	public volatile SSHStatusEnum sshStatus = SSHStatusEnum.AWAIT_CONNECT;
	/**
	 * 是否是主会话
	 */
	private final AtomicBoolean main = new AtomicBoolean(false);
	/**
	 * 最后的会话。volatile 保证 openSession(虚拟线程)与 getSession(event loop)间的可见性
	 */
	private volatile Session lastSession;

	/**
	 * 心跳重入保护：调度器每 500ms 提交一次 sendKeepAliveMsg 到虚拟线程，
	 * 若上一次心跳尚未返回会叠加并发执行，用 CAS 保证同一会话心跳串行。
	 */
	private final AtomicBoolean keepAliveRunning = new AtomicBoolean(false);
	/**
	 * 下次运行时间
	 */
	@Getter
    @Setter
    private long nextRunTime;
	/**
	 * 链接方法
	 */
	private final Runnable connectedFun;
	/**
	 * 端口转发
	 */
	private final PortForwardServer[] forwardServers;
	/**
	 * 绑定端口转发
	 */
	private volatile boolean bandForPort;

	public SSHClientImpl(SSHConfig sshConfig, Runnable connectedFun, PortForwardServer[] forwardServers) {
		super();
		this.sshConfig = sshConfig;
		this.connectedFun = connectedFun;
		this.forwardServers = forwardServers;
	}

	public boolean isConnected() {
		Session session = lastSession;
		return SSHStatusEnum.CONNECTED.equals(sshStatus) && session != null && session.isConnected();
	}

	public void openSession() throws Exception {
		sshStatus = SSHStatusEnum.CONNECTING;
		Logf.log("尝试创建连接%s", sshConfig.toScript());
		// 仅建立 SSH 会话，不再打开 shell 通道：
		// SOCKS5/端口转发走的是 Session 上的 direct-tcpip 通道，与交互式 shell 无关。
		// 部分服务端(如 nologin/ForceCommand/仅允许转发)会立即关闭 shell 通道，
		// 若把会话存活绑定在 shell 上，会导致本可正常转发的会话被误判失效并反复重连。
		Session session = createServerTarget();
		session.setDaemonThread(true);
		session.connect(CONNECT_TIMEOUT);
		lastSession = session;
		Logf.log("创建连接成功%s", sshConfig.toScript());
		sshStatus = SSHStatusEnum.CONNECTED;
		connectedFun.run();
	}

	public void sendKeepAliveMsg() throws Exception {
		// 心跳重入保护：上一次心跳未完成则直接返回
		if (!keepAliveRunning.compareAndSet(false, true)) {
			return;
		}
		try {
			Session session = lastSession;
			if (SSHStatusEnum.CONNECTED.equals(sshStatus) && session != null && session.isConnected()) {
				// 主动探测：在会话传输层发送 SSH keepalive 全局请求，链路已断时会抛异常触发重连。
				// 不再依赖 shell 通道跑命令，避免服务端禁用交互 shell 时误杀健康会话。
				session.sendKeepAliveMsg();
				checkAndBandForPort();
				return;
			}
			// 会话已失效：抛出带诊断信息的异常，明确是状态、会话对象还是底层连接的问题
			throw new RuntimeException(String.format("SSH 会话已失效: status=%s, session=%s", sshStatus,
					session == null ? "null" : ("isConnected=" + session.isConnected())));
		} finally {
			keepAliveRunning.set(false);
		}
	}

	/**
	 * 创建远程句柄
	 * 
	 * @return
	 * @throws JSchException
	 */
	private Session createServerTarget() throws JSchException {
		Session session = jsch.getSession(sshConfig.getUserName(), sshConfig.getAddr(), sshConfig.getPort());
		session.setPassword(sshConfig.getPassword());
		Properties properties = new Properties();
		properties.put("StrictHostKeyChecking", "no");
		session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
		properties.put("TCPKeepAlive", "yes");
		session.setConfig(properties);
		// SSH 层心跳：由 JSch 会话线程按间隔自动发送 keepalive，连续无响应达上限即断开会话，
		// 用于探测静默断链(NAT/防火墙空闲超时等)，无需应用层再开 shell 通道跑命令。
		session.setServerAliveInterval(SERVER_ALIVE_INTERVAL);
		session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
		return session;
	}

	/**
	 * 绑定当前会话为主会话
	 */
	public void bandMainSession() {
		main.set(true);
		for (PortForwardServer server : forwardServers) {
			server.setSshSupplier(this::getSession);
		}
		bandForPort = true;
		checkAndBandForPort();

	}

	// 连接后转发到远程
	private void checkAndBandForPort() {
		if (!bandForPort) {
			return;
		}
		synchronized (this) {
			if (!bandForPort) {
				return;
			}
			Session session = lastSession;
			delPortForwardingR(session);
			try {
				for (CopyItem copyItem : sshConfig.getRemotes()) {
					lastSession.setPortForwardingR(copyItem.getTargetPort(), copyItem.getHost(), copyItem.getPort());
				}
				bandForPort = false;
			} catch (JSchException e) {
			}
			for (CopyItem copyItem : sshConfig.getRemotes()) {
				Logf.log("转发到远程成功 %s", copyItem.toScrpit());
			}
		}
	}

	/**
	 * 销毁链接
	 */
	public void disconnectSession() {
		Logf.log("发生异常，断开连接");
		sshStatus = SSHStatusEnum.AWAIT_CONNECT;
		// 销毁端口转发绑定
		if (main.getAndSet(false)) {
			for (PortForwardServer server : forwardServers) {
				server.setSshSupplier(null);
			}
		}
		// 销毁会话（不再涉及 shell 通道与其输入输出流）
		Session session = lastSession;
		if (session != null) {
			delPortForwardingR(session);
			session.disconnect();
		}
		lastSession = null;
		bandForPort = false;
	}

	private void delPortForwardingR(Session session) {
		if (sshConfig != null) {
			for (CopyItem copyItem : sshConfig.getRemotes()) {
				try {
					session.delPortForwardingR(copyItem.getTargetPort());
				} catch (JSchException _) {
				}
			}
		}
	}

	public Session getSession() {
		return SSHStatusEnum.CONNECTED.equals(sshStatus) ? lastSession : null;
	}

	public boolean isMain() {
		return main.get();
	}

}
