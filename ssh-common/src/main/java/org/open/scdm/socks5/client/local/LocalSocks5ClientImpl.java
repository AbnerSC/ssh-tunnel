package org.open.scdm.socks5.client.local;

import org.open.scdm.common.ssh.ChannelUtils;
import org.open.scdm.common.ssh.Logf;

import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

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
		this.usedSocket = usedSocket;
		// Vert.x 5 移除了 connect(int, String, Handler<AsyncResult<NetSocket>>) 回调重载，只能链 Future
		netClient.connect(port, host).onComplete((res) -> {
			if (res.succeeded()) {
				Logf.log("目标连接成功 %s:%d", host, port);
				NetSocket target = res.result();
				// 先绑定关闭，pipeTo 会覆盖源端的 handler/endHandler/exceptionHandler
				bindClose(target, usedSocket);
				bindClose(usedSocket, target);
				usedSocket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x00 }, hostBytes)).onSuccess((v) -> {
					pipe(target, usedSocket);
					pipe(usedSocket, target);
				});
			} else {
				Logf.log("无法连接请求 %s:%d,%s", host, port, res.cause());
				usedSocket.write(ChannelUtils.joinBuffer(new byte[] { 0x05, 0x03 }, hostBytes));
			}
		});
	}

	/**
	 * 单向转发。Vert.x 5 已删除 io.vertx.core.streams.Pump，改用 ReadStream#pipeTo
	 */
	private void pipe(NetSocket source, NetSocket target) {
		source.pipeTo(target).onFailure((e) -> {
			if (Logf.isLog()) {
				e.printStackTrace();
			}
			target.close();
			Logf.log("连接%s发生异常，同时关闭%s", source.remoteAddress(), target.remoteAddress());
		});
	}

	private void bindClose(NetSocket socket, NetSocket target) {
		socket.closeHandler((res) -> {
			target.close();
			Logf.log("连接%s已关闭，同时关闭%s", socket.remoteAddress(), target.remoteAddress());
		});
	}

}
