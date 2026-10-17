package org.open.scdm.socks5.server;

import io.vertx.core.net.NetSocket;

/**
 * 客户端连接实现者
 */
public interface Socks5ClientConsumer {
	/**
	 * @param hostBytes   回复给客户端的 BND 应答字段（RSV/ATYP/ADDR/PORT），已按请求帧精确截断
	 * @param initialData 客户端与连接请求同段粘连的后续数据（可为 null），
	 *                    必须在目标连通后首先写给目标，否则协议握手首包丢失
	 */
	public void handle(String host, Integer port, byte[] hostBytes, byte[] initialData, NetSocket usedSocket);

	default void close() {
	}
}
