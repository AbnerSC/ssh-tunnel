package org.open.scdm.socks5.client.local;

import org.open.scdm.common.vertx.VertxUtil;
import org.open.scdm.socks5.server.Socks5ClientConsumer;

import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

/**
 * 本地socks5实现
 */
public class LocalSocks5Client implements Socks5ClientConsumer {
	/**
	 * 客户端
	 */
	private final NetClient netClient;

	public LocalSocks5Client() {
		// connectTimeout 1000ms 对跨广域网目标偏短，适当放宽到 3000ms 减少误判连接失败；
		// tcpNoDelay 降低转发延迟，reuseAddress 便于端口快速复用。
		NetClientOptions options = new NetClientOptions()
				.setConnectTimeout(3000)
				.setTcpNoDelay(true)
				.setReuseAddress(true);
		this.netClient = VertxUtil.current().getVertx().createNetClient(options);
	}

	@Override
	public void handle(String host, Integer port, byte[] hostBytes, NetSocket usedSocket) {
		new LocalSocks5ClientImpl(host, port, hostBytes, usedSocket, netClient);
	}

	@Override
	public void close() {
		netClient.close();
	}

}
