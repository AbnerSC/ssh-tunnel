package org.open.scdm.route;

import org.open.scdm.common.config.DirectRouteConfig;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.socks5.server.Socks5ClientConsumer;

import io.vertx.core.net.NetSocket;

/**
 * socks5 直连路由：命中 {@link DirectRouteConfig} 的目标交给本地直连实现，
 * 其余走 SSH 隧道实现；未配置远程服务器时全部本地直连。
 */
public class DirectRouteSocks5Client implements Socks5ClientConsumer {
	/**
	 * 直连规则，可为 null（视为不命中任何直连规则）
	 */
	private final DirectRouteConfig config;
	/**
	 * 本地直连实现
	 */
	private final Socks5ClientConsumer direct;
	/**
	 * SSH 隧道实现，可为 null（无远程服务器，全部本地直连）
	 */
	private final Socks5ClientConsumer tunnel;

	public DirectRouteSocks5Client(DirectRouteConfig config, Socks5ClientConsumer direct,
			Socks5ClientConsumer tunnel) {
		this.config = config;
		this.direct = direct;
		this.tunnel = tunnel;
	}

	@Override
	public void handle(String host, Integer port, byte[] hostBytes, NetSocket usedSocket) {
		if (tunnel == null || config != null && config.isDirect(host)) {
			Logf.log("socks5直连 %s:%d", host, port);
			direct.handle(host, port, hostBytes, usedSocket);
			return;
		}
		tunnel.handle(host, port, hostBytes, usedSocket);
	}

	@Override
	public void close() {
		direct.close();
		if (tunnel != null) {
			tunnel.close();
		}
	}
}
