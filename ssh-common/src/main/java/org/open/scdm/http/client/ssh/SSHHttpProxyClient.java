package org.open.scdm.http.client.ssh;

import org.open.scdm.common.ssh.ChannelUtils;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.ssh.SSHClientPool;
import org.open.scdm.common.vertx.VertxUtil;
import org.open.scdm.http.server.HttpProxyClientConsumer;

import com.jcraft.jsch.Session;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * 基于 SSH 隧道的 http 代理实现：通过 SSH direct-tcpip 通道连接目标主机。
 * <p>
 * 结构与 {@code SSHSocks5Client} 一致，区别在于应答内容由调用方给出（见 HttpProxyClientConsumer）。
 */
public class SSHHttpProxyClient implements HttpProxyClientConsumer {
	/**
	 * 会话
	 */
	private SSHClientPool clientPool;

	public SSHHttpProxyClient(SSHClientPool clientPool) {
		super();
		this.clientPool = clientPool;
	}

	@Override
	public void handle(String host, int port, byte[] initialToTarget, byte[] replyToClient, byte[] failReplyToClient,
			NetSocket socket) {
		Session session = clientPool.trySession();
		if (session != null && session.isConnected()) {
			socket.pause();
			VertxUtil.current().pushTask(() -> {
				try {
					// 复用 socks5/端口转发相同的通道建立逻辑，initialToTarget 在通道连通后先写给目标
					ChannelUtils.openChannel(host, port, session, socket, initialToTarget);
					Logf.log("http代理目标连接成功 %s:%d", host, port);
					socket.resume();
					if (replyToClient != null && replyToClient.length > 0) {
						socket.write(Buffer.buffer(replyToClient));
					}
				} catch (Exception e) {
					Logf.log("http代理无法连接请求 %s:%d", host, port);
					socket.resume();
					fail(socket, failReplyToClient);
				}
			});
			return;
		}
		fail(socket, failReplyToClient);
	}

	private void fail(NetSocket socket, byte[] failReplyToClient) {
		if (failReplyToClient != null && failReplyToClient.length > 0) {
			socket.write(Buffer.buffer(failReplyToClient)).onComplete((w) -> socket.close());
		} else {
			socket.close();
		}
	}

	@Override
	public void close() {

	}

}
