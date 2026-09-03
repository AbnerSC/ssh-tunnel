package org.open.scdm.common.ssh;

import java.io.IOException;
import java.util.function.Supplier;

import org.open.scdm.common.config.CopyItem;
import org.open.scdm.common.vertx.VertxUtil;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;

/**
 * 端口转发服务
 */
public class PortForwardServer {

	/**
	 * 
	 */
	private Supplier<Session> sshSupplier;
	/**
	 * 端口
	 */
	private CopyItem copyItem;

	public PortForwardServer(CopyItem copyItem) {
		this.copyItem = copyItem;
		Vertx vertx = VertxUtil.current().getVertx();
		final int port = copyItem.getTargetPort();
		// 同 SOCKS5 服务：tcpNoDelay 降延迟，acceptBacklog 抗高并发瞬时连接，reuseAddress 便于重启
		NetServerOptions serverOptions = new NetServerOptions()
				.setTcpNoDelay(true)
				.setReuseAddress(true)
				.setAcceptBacklog(1024);
		vertx.createNetServer(serverOptions)
				// 当被连接时
				.connectHandler(c -> {
					c.pause();
					VertxUtil.current().pushTask(() -> connect(c));
				})
				// 监听
				.listen(port).onComplete(res -> {
					if (res.succeeded()) {
						Logf.printf("本地转发端口%d监听成功", port);
					} else {
						Logf.printf(res.cause().getMessage());
						Logf.printf("本地转发%d监听失败", port);
					}
				});
	}

	private void connect(NetSocket socket) {
		Session session = sshSupplier == null ? null : sshSupplier.get();
		if (session != null && session.isConnected()) {
			try {
				ChannelUtils.openChannel(copyItem.getHost(), copyItem.getPort(), session, socket);
				socket.resume();
				return;
			} catch (JSchException | IOException e) {
				e.printStackTrace();
			}
		}
		socket.close();
	}

	public void setSshSupplier(Supplier<Session> sshSupplier) {
		this.sshSupplier = sshSupplier;
	}

	public void close() {
	}
}
