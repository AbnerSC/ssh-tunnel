package org.open.scdm.http.client.local;

import org.open.scdm.common.vertx.VertxUtil;
import org.open.scdm.http.server.HttpProxyClientConsumer;

import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

/**
 * 本地 http 代理实现：直接通过 NetClient 连接目标主机（无 SSH 隧道）。
 */
public class LocalHttpProxyClient implements HttpProxyClientConsumer {
	/**
	 * 客户端
	 */
	private final NetClient netClient;

	public LocalHttpProxyClient() {
		// 与本地 socks5 实现保持一致的连接参数
		NetClientOptions options = new NetClientOptions()
				.setConnectTimeout(3000)
				.setTcpNoDelay(true)
				.setReuseAddress(true);
		this.netClient = VertxUtil.current().getVertx().createNetClient(options);
	}

	@Override
	public void handle(String host, int port, byte[] initialToTarget, byte[] replyToClient, byte[] failReplyToClient,
			NetSocket usedSocket) {
		new LocalHttpProxyClientImpl(host, port, initialToTarget, replyToClient, failReplyToClient, usedSocket,
				netClient);
	}

	@Override
	public void close() {
		netClient.close();
	}

}
