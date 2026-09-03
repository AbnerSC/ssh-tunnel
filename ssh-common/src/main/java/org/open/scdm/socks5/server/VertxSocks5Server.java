package org.open.scdm.socks5.server;

import org.open.scdm.common.config.StrUtil;
import org.open.scdm.common.vertx.VertxUtil;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;

/**
 * socks5服务器
 */
public class VertxSocks5Server {
	/**
	 * 服务
	 */
	private NetServer netServer;
	/**
	 * 账号
	 */
	private String userName;
	/**
	 * 密码
	 */
	private String password;

	private Socks5ClientConsumer clientConsumer;
	private boolean auth;

	public VertxSocks5Server(String userName, String password, Socks5ClientConsumer clientConsumer) {
		super();
		this.userName = userName;
		this.password = password;
		auth = StrUtil.isNotEmpty(userName) && StrUtil.isNotEmpty(password);
		this.clientConsumer = clientConsumer;
	}

	public void start(int port) {
		Vertx vertx = VertxUtil.current().getVertx();
		// tcpNoDelay 关闭 Nagle，降低代理转发延迟；acceptBacklog 增大半连接队列，
		// 避免高并发瞬时大量连接请求被内核拒接；reuseAddress 便于快速重启。
		NetServerOptions serverOptions = new NetServerOptions()
				.setTcpNoDelay(true)
				.setReuseAddress(true)
				.setAcceptBacklog(1024);
		netServer = vertx.createNetServer(serverOptions);
		netServer
				// 处理链路
				.connectHandler(c -> new VertxSocks5Impl(auth, userName, password, clientConsumer, c))
				// 监听
				.listen(port).onComplete(res -> {
					System.out.print("socks5服务启动");
					if (res.succeeded()) {
						System.out.print("成功");
					} else {
						netServer.close();
						System.out.print("失败");
					}
					System.out.println("端口:" + port);
				});
	}

	public void close() {
		netServer.close();
	}
}
