package priv.dm.socks5.server;

import io.vertx.core.net.NetSocket;

/**
 * 客户端连接实现者
 */
public interface Socks5ClientConsumer {
	public void handle(String host, Integer port, byte[] hostBytes, NetSocket usedSocket);

	default void close() {
	}
}
