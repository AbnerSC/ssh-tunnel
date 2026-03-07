package priv.dm.common.ssh;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import priv.dm.common.config.CopyItem;
import priv.dm.common.config.SSHConfig;
import priv.dm.common.config.StrUtil;
import com.jcraft.jsch.ChannelShell;
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
	private static JSch jsch = new JSch();
	/**
	 * 待连接
	 */
	public SSHStatusEnum sshStatus = SSHStatusEnum.AWAIT_CONNECT;
	/**
	 * 是否是主会话
	 */
	private final AtomicBoolean main = new AtomicBoolean(false);
	/**
	 * 最后的会话
	 */
	private Session lastSession;

	/**
	 * 字符界面
	 */
	private ChannelShell channelShell;
	/**
	 * 输入流
	 */
	private InputStream inputStream;
	/**
	 * 输出流
	 */
	private OutputStream outputStream;
	/**
	 * 下次运行时间
	 */
	private long nextRunTime;
	/**
	 * 链接方法
	 */
	private final Runnable connectedFun;
	/**
	 * 端口转发
	 */
	private PortForwardServer[] forwardServers;
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
		return SSHStatusEnum.CONNECTED.equals(sshStatus) && lastSession != null && channelShell != null
				&& channelShell.isConnected();
	}

	public void openSession() throws Exception {
		sshStatus = SSHStatusEnum.CONNECTING;
		Logf.log("尝试创建连接%s", sshConfig.toScript());
		// 创建连接
		lastSession = createServerTarget();
		lastSession.setDaemonThread(true);
		lastSession.connect();
		channelShell = (ChannelShell) lastSession.openChannel("shell");
		inputStream = channelShell.getInputStream();
		channelShell.setPty(true);
		channelShell.connect();
		outputStream = channelShell.getOutputStream();
		Logf.log("创建连接成功%s", sshConfig.toScript());
		sshStatus = SSHStatusEnum.CONNECTED;
		connectedFun.run();
	}

	public void sendKeepAliveMsg() throws Exception {
		if (isConnected()) {
//			System.out.println("心跳");
			lastSession.sendKeepAliveMsg();
			if (inputStream.available() > 0) {
				byte[] bytes = new byte[8192];
				int a = inputStream.read(bytes);
				if (a != -1) {
					String str = new String(bytes, 0, a);
					if (StrUtil.isEmpty(str)) {
						throw new RuntimeException();
					}
				}
			}
			outputStream.write("free -h\r\n".getBytes());
			outputStream.flush();
			sshStatus = SSHStatusEnum.CONNECTED;
			checkAndBandForPort();
			return;
		}
		throw new RuntimeException();
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
				Logf.log("转发到远程成功", copyItem.toScrpit());
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
		// 销毁终端
		closed(inputStream);
		closed(outputStream);
		inputStream = null;
		outputStream = null;
		if (channelShell != null) {
			channelShell.disconnect();
		}
		channelShell = null;
		// 销毁会话
		Session session = lastSession;
		if (session != null) {
			delPortForwardingR(session);
			session.disconnect();
		}
		lastSession = null;
		bandForPort=false;
	}

	private void delPortForwardingR(Session session) {
		if (sshConfig != null) {
			for (CopyItem copyItem : sshConfig.getRemotes()) {
				try {
					session.delPortForwardingR(copyItem.getTargetPort());
				} catch (JSchException e) {
				}
			}
		}
	}

	public void closed(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException e) {
			}
		}
	}

	public Session getSession() {
		return SSHStatusEnum.CONNECTED.equals(sshStatus) ? lastSession : null;
	}

	public boolean isMain() {
		return main.get();
	}

	public void setNextRunTime(long nextRunTime) {
		this.nextRunTime = nextRunTime;
	}

	public long getNextRunTime() {
		return nextRunTime;
	}

}
