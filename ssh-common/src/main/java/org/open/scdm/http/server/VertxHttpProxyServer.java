package org.open.scdm.http.server;

import org.open.scdm.common.config.StrUtil;
import org.open.scdm.common.vertx.VertxUtil;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;

/**
 * http 代理服务器：在指定端口监听，接收客户端 HTTP/HTTPS(CONNECT) 代理请求。
 */
public class VertxHttpProxyServer {
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

	private HttpProxyClientConsumer clientConsumer;
	private boolean auth;

	public VertxHttpProxyServer(String userName, String password, HttpProxyClientConsumer clientConsumer) {
		super();
		this.userName = userName;
		this.password = password;
		auth = StrUtil.isNotEmpty(userName) && StrUtil.isNotEmpty(password);
		this.clientConsumer = clientConsumer;
	}

	public void start(int port) {
		Vertx vertx = VertxUtil.current().getVertx();
		// 同 socks5 服务：tcpNoDelay 降延迟，acceptBacklog 抗高并发瞬时连接，reuseAddress 便于重启
		NetServerOptions serverOptions = new NetServerOptions()
				.setTcpNoDelay(true)
				.setReuseAddress(true)
				.setAcceptBacklog(1024);
		netServer = vertx.createNetServer(serverOptions);
		netServer
				// 处理链路
				.connectHandler(c -> new VertxHttpProxyImpl(auth, userName, password, clientConsumer, c))
				// 监听
				.listen(port).onComplete(res -> {
					System.out.print("http代理服务启动");
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
