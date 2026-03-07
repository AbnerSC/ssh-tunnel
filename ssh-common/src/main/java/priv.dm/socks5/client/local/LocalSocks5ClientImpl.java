package priv.dm.socks5.client.local;

import priv.dm.common.ssh.ChannelUtils;
import priv.dm.common.ssh.Logf;

import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.streams.Pump;

/**
 * 本地socks客户端实现
 */
class LocalSocks5ClientImpl {
	/**
	 * 用户连接
	 */
	NetSocket usedSocket;

	public LocalSocks5ClientImpl(String host, Integer port, byte[] hostBytes, NetSocket usedSocket,
			NetClient netClient) {
		netClient.connect(port, host, (res) -> {
			if (res.succeeded()) {
				Logf.log("目标连接成功 %s:%d", host, port);
				NetSocket target = res.result();
				usedSocket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x00 }, hostBytes), (v) -> {
					Pump.pump(target, usedSocket).start();
					Pump.pump(usedSocket, target).start();
				});
				// 关闭
				bindClose(target, usedSocket);
				bindClose(usedSocket, target);
			} else {
				Logf.log("无法连接请求 %s:%d", host, port);
				usedSocket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x03 }, hostBytes));
			}
		});
	}

	private void bindClose(NetSocket socket, NetSocket target) {
		socket.closeHandler((res) -> {
			target.close();
			Logf.log("连接%s已关闭，同时关闭%s", socket.remoteAddress(), target.remoteAddress());
		});
		socket.exceptionHandler((res) -> {
			if (res.getCause() != null) {
				res.getCause().printStackTrace();
			}
			target.close();
			Logf.log("连接%s发生异常，同时关闭%s", socket.remoteAddress(), target.remoteAddress());
		});
	}

}
