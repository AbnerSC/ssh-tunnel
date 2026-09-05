package org.open.scdm.http.server;

import io.vertx.core.net.NetSocket;

/**
 * http代理目标连接实现者。
 * <p>
 * 与 socks5 的 {@code Socks5ClientConsumer} 语义一致：负责建立到目标 host:port 的连接并做双向转发。
 * 区别在于 http 代理的应答/首包内容由协议决定，故由调用方（{@code VertxHttpProxyImpl}）显式给出：
 * <ul>
 * <li>{@code initialToTarget}：连接成功后先写给目标的字节。CONNECT 场景为握手包内残留的 TLS 字节；
 * 普通 HTTP 转发场景为改写后的请求报文。可为 null。</li>
 * <li>{@code replyToClient}：连接成功后写给客户端的应答。CONNECT 场景为
 * {@code HTTP/1.1 200 Connection Established}；普通 HTTP 转发无需应答（客户端等待目标响应）。可为 null。</li>
 * <li>{@code failReplyToClient}：连接失败时写给客户端的应答，通常为 {@code HTTP/1.1 502 Bad Gateway}。
 * 为 null 时直接关闭连接。</li>
 * </ul>
 */
public interface HttpProxyClientConsumer {
	void handle(String host, int port, byte[] initialToTarget, byte[] replyToClient, byte[] failReplyToClient,
			NetSocket usedSocket);

	default void close() {
	}
}
