package org.open.scdm.route;

import org.open.scdm.common.config.DirectRouteConfig;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.http.server.HttpProxyClientConsumer;

import io.vertx.core.net.NetSocket;

/**
 * http 代理直连路由：命中 {@link DirectRouteConfig} 的目标交给本地直连实现，
 * 其余走 SSH 隧道实现；未配置远程服务器时全部本地直连。
 * <p>
 * 与 {@link DirectRouteSocks5Client} 语义一致，仅接口不同。
 */
public class DirectRouteHttpProxyClient implements HttpProxyClientConsumer {
	/**
	 * 直连规则，可为 null（视为不命中任何直连规则）
	 */
	private final DirectRouteConfig config;
	/**
	 * 本地直连实现
	 */
	private final HttpProxyClientConsumer direct;
	/**
	 * SSH 隧道实现，可为 null（无远程服务器，全部本地直连）
	 */
	private final HttpProxyClientConsumer tunnel;

	public DirectRouteHttpProxyClient(DirectRouteConfig config, HttpProxyClientConsumer direct,
			HttpProxyClientConsumer tunnel) {
		this.config = config;
		this.direct = direct;
		this.tunnel = tunnel;
	}

	@Override
	public void handle(String host, int port, byte[] initialToTarget, byte[] replyToClient, byte[] failReplyToClient,
			NetSocket usedSocket) {
		if (tunnel == null || config != null && config.isDirect(host)) {
			Logf.log("http代理直连 %s:%d", host, port);
			direct.handle(host, port, initialToTarget, replyToClient, failReplyToClient, usedSocket);
			return;
		}
		tunnel.handle(host, port, initialToTarget, replyToClient, failReplyToClient, usedSocket);
	}

	@Override
	public void close() {
		direct.close();
		if (tunnel != null) {
			tunnel.close();
		}
	}
}
