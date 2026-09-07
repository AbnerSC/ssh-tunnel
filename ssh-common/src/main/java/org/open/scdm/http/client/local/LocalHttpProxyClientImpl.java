package org.open.scdm.http.client.local;

import org.open.scdm.common.ssh.Logf;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

/**
 * 本地 http 代理连接实现：连接目标后按需写入首包、应答客户端，并做双向转发。
 * <p>
 * 结构与 {@code LocalSocks5ClientImpl} 一致，区别在于应答内容由调用方给出（见 HttpProxyClientConsumer）。
 */
class LocalHttpProxyClientImpl {

	public LocalHttpProxyClientImpl(String host, int port, byte[] initialToTarget, byte[] replyToClient,
			byte[] failReplyToClient, NetSocket usedSocket, NetClient netClient) {
		// Vert.x 5 移除了 connect(int, String, Handler) 回调重载，只能链 Future
		netClient.connect(port, host).onComplete((res) -> {
			if (res.succeeded()) {
				Logf.log("http代理目标连接成功 %s:%d", host, port);
				NetSocket target = res.result();
				// 先绑定关闭，pipeTo 会覆盖源端的 handler/endHandler/exceptionHandler
				bindClose(target, usedSocket);
				bindClose(usedSocket, target);
				// 连接成功后先把首包写给目标（普通 HTTP 转发的改写请求 / CONNECT 残留字节）
				if (initialToTarget != null && initialToTarget.length > 0) {
					target.write(Buffer.buffer(initialToTarget));
				}
				if (replyToClient != null && replyToClient.length > 0) {
					// CONNECT：先回 200 再开始转发，客户端收到后才发 TLS 数据
					usedSocket.write(Buffer.buffer(replyToClient)).onSuccess((v) -> {
						startPipe(target, usedSocket);
					});
				} else {
					startPipe(target, usedSocket);
				}
			} else {
				Logf.log("http代理无法连接请求 %s:%d,%s", host, port, res.cause());
				if (failReplyToClient != null && failReplyToClient.length > 0) {
					usedSocket.write(Buffer.buffer(failReplyToClient)).onComplete((w) -> usedSocket.close());
				} else {
					usedSocket.close();
				}
			}
		});
	}

	/**
	 * 开始双向转发，并恢复被暂停的客户端连接
	 */
	private void startPipe(NetSocket target, NetSocket usedSocket) {
		pipe(target, usedSocket);
		pipe(usedSocket, target);
		// VertxHttpProxyImpl 派发前会 pause 客户端连接，转发链路就绪后恢复
		usedSocket.resume();
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
