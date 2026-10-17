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
	public void handle(String host, Integer port, byte[] hostBytes, byte[] initialData, NetSocket socket) {
		ClientSession session = clientPool.trySession();
		// trySession 只会返回健康会话(isClosed/isClosing 均为 false)，无需再校验连接状态
		if (session != null) {
			socket.pause();
			VertxUtil.current().pushTask(() -> {
				try {
					// 粘连在连接请求后的首包先写给目标，再恢复客户端后续数据的转发
					ChannelUtils.openChannel(host, port, session, socket, initialData);
					Logf.log("目标连接成功 %s:%d", host, port);
					socket.resume();
					socket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x00 }, hostBytes));
				} catch (Exception e) {
					// %s 打印 e.toString()：超时为 TimeoutException，服务端拒绝为 SshChannelOpenException(带 reason)，可据此区分故障
					Logf.log("无法连接请求 %s:%d,%s", host, port, e);
					writeFailAndClose(socket, hostBytes);
				}
			});
			return;
		}
		// 无可用会话（SSH 断链/重连中）：回完失败应答必须关闭连接。
		// 握手阶段的超时定时器已取消，此处不关则 socket 永久悬挂，
		// 长时间运行下叠加耗尽文件句柄，表现为整个代理不再接受新连接
		writeFailAndClose(socket, hostBytes);
	}

	/**
	 * 回复连接失败应答并关闭连接
	 */
	private void writeFailAndClose(NetSocket socket, byte[] hostBytes) {
		socket.resume();
		socket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x03 }, hostBytes))
				.onComplete((v) -> socket.close());
	}

	@Override
	public void close() {

	}

}
