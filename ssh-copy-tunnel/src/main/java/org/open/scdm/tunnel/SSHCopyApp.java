package org.open.scdm.tunnel;

import org.open.scdm.common.config.DES3Util;
import org.open.scdm.common.config.ParamFormat;
import org.open.scdm.common.config.SSHConfig;
import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.ssh.SSHClientPool;
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
		Socks5ClientConsumer socks5Client;
		if (config.getSshConfig() != null) {
			SSHClientPool client = new SSHClientPool(config.getSshConfig());
			client.start();
			socks5Client = new SSHSocks5Client(client);
		} else {
			socks5Client = new LocalSocks5Client();
		}
		if (config.getSocksPort() > 0) {
			new VertxSocks5Server(config.getSocksUserName(), config.getSocksPassword(), socks5Client)
					.start(config.getSocksPort());
		}
	}

	/**
	 * 检查是不是加密
	 *
	 * @param format
	 * @return
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
