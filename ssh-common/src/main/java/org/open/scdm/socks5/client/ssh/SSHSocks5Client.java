package org.open.scdm.socks5.client.ssh;

import org.open.scdm.common.ssh.ChannelUtils;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.ssh.SSHClientPool;
import org.open.scdm.common.vertx.VertxUtil;
import org.apache.sshd.client.session.ClientSession;
import org.open.scdm.socks5.server.Socks5ClientConsumer;

import io.vertx.core.net.NetSocket;

/**
 * 本地socks客户端实现
 */
public class SSHSocks5Client implements Socks5ClientConsumer {
	/**
	 * 会话
	 */
	private SSHClientPool clientPool;

	public SSHSocks5Client(SSHClientPool clientPool) {
		super();
		this.clientPool = clientPool;
	}

	@Override
	public void handle(String host, Integer port, byte[] hostBytes, NetSocket socket) {
		ClientSession session = clientPool.trySession();
		// trySession 只会返回健康会话(isClosed/isClosing 均为 false)，无需再校验连接状态
		if (session != null) {
			socket.pause();
			VertxUtil.current().pushTask(() -> {
				try {
					ChannelUtils.openChannel(host, port, session, socket);
					Logf.log("目标连接成功 %s:%d", host, port);
					socket.resume();
					socket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x00 }, hostBytes));
				} catch (Exception e) {
					Logf.log("无法连接请求 %s:%d", host, port);
					socket.resume();
					// 关闭
					socket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x03 }, hostBytes));
				}
			});
			return;
		}
		// 关闭
		socket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x03 }, hostBytes));
	}

	@Override
	public void close() {

	}

}
