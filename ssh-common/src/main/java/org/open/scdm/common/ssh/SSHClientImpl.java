package org.open.scdm.common.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import lombok.Setter;
import org.open.scdm.common.config.CopyItem;
import org.open.scdm.common.config.SSHConfig;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.Cipher;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;

public class SSHClientImpl {
	/**
	 * 配置
	 */
	private final SSHConfig sshConfig;
	/**
	 * 共享 SSH 客户端：一个 SshClient 可承载全部池内会话，NIO2 连接器按核数复用 worker 线程。
	 * 静态初始化一次并 start，会话关闭不影响客户端本身。
	 */
	private static final SshClient SSH_CLIENT = createClient();
	/**
	 * 会话连接超时(ms)：TCP + KEX + 认证各阶段上限，避免 connect()/auth() 无限阻塞虚拟线程
	 */
	private static final int CONNECT_TIMEOUT = 10000;
	/**
	 * SSH 层心跳间隔(ms)：由 MINA 会话自动发送 keepalive 全局请求并探测失效。
	 */
	private static final long SERVER_ALIVE_INTERVAL = 15000;
	/**
	 * 连续多少次心跳无响应即判定会话失效
	 */
	private static final int SERVER_ALIVE_COUNT_MAX = 3;
	/**
	 * 远程端口转发在服务器侧的绑定地址，与 JSch setPortForwardingR(port,...) 的默认行为一致
	 */
	private static final String REMOTE_BIND_HOST = "localhost";
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
	private volatile ClientSession lastSession;
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

	/**
	 * 创建全局共享客户端。性能相关配置集中于此：
	 * <ul>
	 * <li>AEAD 密码套件优先：MINA 默认顺序(ssh_config)把 CTR 排在 GCM 前，
	 * Java 的 AES-GCM 有 AES-NI 内建加速且免独立 MAC 计算，明确调到最前</li>
	 * <li>通道流控窗口：默认 2MB/32KB 包(与 OpenSSH 对齐)，远大于 JSch 的 128KB/16KB，
	 * 高 RTT 链路下单通道吞吐不再被 窗口/RTT 锁死，无需额外配置</li>
	 * <li>TCP_NODELAY：MINA 默认关闭，会引入 Nagle 合包延迟，转发场景必须开启</li>
	 * <li>心跳：15s 一次、连续 3 次无响应判死，静默断链(NAT/防火墙空闲超时)约 45s 内触发重连</li>
	 * </ul>
	 */
	private static SshClient createClient() {
		SshClient client = SshClient.setUpDefaultClient();
		// 对应原 JSch StrictHostKeyChecking=no
		client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
		// 优选 AEAD，其后保留 CTR 与 ChaCha20 作兼容回退（均为 JDK 25 内置支持的套件）
		List<NamedFactory<Cipher>> ciphers = Arrays.asList(
				BuiltinCiphers.aes128gcm, BuiltinCiphers.aes256gcm,
				BuiltinCiphers.aes128ctr, BuiltinCiphers.aes192ctr, BuiltinCiphers.aes256ctr,
				BuiltinCiphers.cc20p1305_openssh,
				BuiltinCiphers.aes128cbc, BuiltinCiphers.aes192cbc, BuiltinCiphers.aes256cbc);
		client.setCipherFactories(ciphers);
		CoreModuleProperties.TCP_NODELAY.set(client, Boolean.TRUE);
		CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofMillis(SERVER_ALIVE_INTERVAL));
		CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, SERVER_ALIVE_COUNT_MAX);
		client.start();
		return client;
	}

	public boolean isConnected() {
		ClientSession session = lastSession;
		// sshStatus==CONNECTED 即代表认证已通过；isClosed/isClosing 覆盖心跳判死、网络断开等失效场景
		return SSHStatusEnum.CONNECTED.equals(sshStatus) && session != null && !session.isClosed()
				&& !session.isClosing();
	}

	public void openSession() throws Exception {
		sshStatus = SSHStatusEnum.CONNECTING;
		Logf.log("尝试创建连接%s", sshConfig.toScript());
		// 仅建立 SSH 会话：SOCKS5/端口转发走 Session 上的 direct-tcpip 通道，与交互式 shell 无关
		ClientSession session = SSH_CLIENT
				.connect(sshConfig.getUserName(), sshConfig.getAddr(), sshConfig.getPort())
				.verify(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
				.getSession();
		try {
			session.addPasswordIdentity(sshConfig.getPassword());
			// keyboard-interactive 兜底：部分服务端(PAM)只接受交互式认证
			session.setUserInteraction(new PasswordUserInteraction(sshConfig.getPassword()));
			session.auth().verify(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			session.close(true);
			throw e;
		}
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
			// keepalive 报文由 MINA 按 HEARTBEAT_INTERVAL 自动发送；
			// 此处仅做健康检查：链路已断(isClosed)时抛异常触发上层重连
			if (isConnected()) {
				checkAndBandForPort();
				return;
			}
			// 会话已失效：抛出带诊断信息的异常，明确是状态、会话对象还是底层连接的问题
			ClientSession session = lastSession;
			throw new RuntimeException(String.format("SSH 会话已失效: status=%s, session=%s", sshStatus,
					session == null ? "null"
							: ("isClosed=" + session.isClosed() + ",isClosing=" + session.isClosing())));
		} finally {
			keepAliveRunning.set(false);
		}
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
			ClientSession session = lastSession;
			if (session == null || session.isClosed()) {
				return;
			}
			delPortForwardingR(session);
			try {
				for (CopyItem copyItem : sshConfig.getRemotes()) {
					// remote=服务器侧监听地址，local=由本机连接的目标地址，语义与 ssh -R targetPort:host:port 一致
					session.startRemotePortForwarding(
							new SshdSocketAddress(REMOTE_BIND_HOST, copyItem.getTargetPort()),
							new SshdSocketAddress(copyItem.getHost(), copyItem.getPort()));
				}
				bandForPort = false;
			} catch (IOException e) {
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
		ClientSession session = lastSession;
		if (session != null) {
			delPortForwardingR(session);
			session.close(true);
		}
		lastSession = null;
		bandForPort = false;
	}

	private void delPortForwardingR(ClientSession session) {
		if (sshConfig != null && session != null) {
			for (CopyItem copyItem : sshConfig.getRemotes()) {
				try {
					session.stopRemotePortForwarding(
							new SshdSocketAddress(REMOTE_BIND_HOST, copyItem.getTargetPort()));
				} catch (IOException _) {
				}
			}
		}
	}

	public ClientSession getSession() {
		return SSHStatusEnum.CONNECTED.equals(sshStatus) ? lastSession : null;
	}

	public boolean isMain() {
		return main.get();
	}

	/**
	 * keyboard-interactive 认证兜底：所有非回显提问一律以密码应答（PAM 常见的多段问答）
	 */
	private static final class PasswordUserInteraction implements UserInteraction {
		private final String password;

		PasswordUserInteraction(String password) {
			this.password = password;
		}

		@Override
		public String[] interactive(ClientSession session, String name, String instruction, String lang,
				String[] prompt, boolean[] echo) {
			String[] res = new String[prompt.length];
			Arrays.fill(res, password);
			return res;
		}

		@Override
		public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
			// 不支持服务端要求的改密流程，交由上层按认证失败处理
			return null;
		}
	}

}
