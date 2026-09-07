package org.open.scdm.tunnel;

import org.open.scdm.common.config.DES3Util;
import org.open.scdm.common.config.ParamFormat;
import org.open.scdm.common.config.SSHConfig;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.ssh.SSHClientPool;
import org.open.scdm.http.client.local.LocalHttpProxyClient;
import org.open.scdm.http.client.ssh.SSHHttpProxyClient;
import org.open.scdm.http.server.HttpProxyClientConsumer;
import org.open.scdm.http.server.VertxHttpProxyServer;
import org.open.scdm.route.DirectRouteHttpProxyClient;
import org.open.scdm.route.DirectRouteSocks5Client;
import org.open.scdm.socks5.client.local.LocalSocks5Client;
import org.open.scdm.socks5.client.ssh.SSHSocks5Client;
import org.open.scdm.socks5.server.Socks5ClientConsumer;
import org.open.scdm.socks5.server.VertxSocks5Server;

public class SSHCopyApp {
	static void main(String[] args) {
		ParamFormat format = new ParamFormat(args);
		Logf.setLog(true);
		if (checkPassword(format)) {
			return;
		}
		new SSHCopyApp().run(format);
	}

	private void run(ParamFormat format) {
		SSHCopyConfig config = new SSHCopyConfig(format);
		if (!config.checkWork()) {
			Logf.printf("找不到可以工作的任务");
			System.exit(0);
			return;
		}
		// socks5 与 http 代理共用同一个 SSH 连接池（若配置了服务器）；
		// 未配置服务器时本机直接提供代理（全部本地直连），
		// 配置了直连规则（-direct-ip/-direct-domain）时命中规则的请求本地直连，其余走 SSH 隧道
		SSHClientPool client = null;
		if (config.getSshConfig() != null) {
			client = new SSHClientPool(config.getSshConfig());
			client.start();
		}
		Socks5ClientConsumer socks5Client = new DirectRouteSocks5Client(config.getDirectRoute(),
				new LocalSocks5Client(), client != null ? new SSHSocks5Client(client) : null);
		HttpProxyClientConsumer httpClient = new DirectRouteHttpProxyClient(config.getDirectRoute(),
				new LocalHttpProxyClient(), client != null ? new SSHHttpProxyClient(client) : null);
		if (config.getSocksPort() > 0) {
			new VertxSocks5Server(config.getSocksUserName(), config.getSocksPassword(), socks5Client)
					.start(config.getSocksPort());
		}
		if (config.getHttpPort() > 0) {
			new VertxHttpProxyServer(config.getSocksUserName(), config.getSocksPassword(), httpClient)
					.start(config.getHttpPort());
		}
	}

	/**
	 * 检查是不是加密
	 */
	private static boolean checkPassword(ParamFormat format) {
		String key = "-encode";
		if (format.containsKey(key)) {
			key = format.getValue(key);
			for (int i = 0; i < 4; i++) {
				key = DES3Util.encode(key, SSHConfig.CYPHER_KEY);
			}
			System.out.println(key);
			return true;
		}
		return false;
	}
}
