package priv.dm.socks5.server;

import priv.dm.common.config.StrUtil;
import priv.dm.common.vertx.VertxUtil;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;

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
		netServer = vertx.createNetServer();
		netServer
				// 处理链路
				.connectHandler(c -> new VertxSocks5Impl(auth, userName, password, clientConsumer, c))
				// 监听
				.listen(port, res -> {
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
