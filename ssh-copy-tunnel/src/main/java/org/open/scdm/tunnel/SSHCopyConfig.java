package org.open.scdm.tunnel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.open.scdm.common.config.CopyItem;
import org.open.scdm.common.config.DES3Util;
import org.open.scdm.common.config.ParamFormat;
import org.open.scdm.common.config.SSHConfig;
import org.open.scdm.common.config.StrUtil;
import org.open.scdm.common.console.MyConsoleCollect;
import org.open.scdm.common.ssh.Logf;

import lombok.Data;

/**
 * 服务配置
 */
@Data
public class SSHCopyConfig {
	/**
	 * 验证密码
	 */
	private String socksUserName;
	/**
	 * 验证账号
	 */
	private String socksPassword;
	/**
	 * 地址
	 */
	private Integer socksPort;
	/**
	 * http代理监听端口(-H)，与 socks5 复用同一套账号密码认证
	 */
	private Integer httpPort;
	/**
	 * ssh配置
	 */
	private SSHConfig sshConfig;

	public SSHCopyConfig(ParamFormat format) {
		// 端口
		int serverPort = Integer.parseInt(format.getValue("-p", "22"));
		// 服务器地址
		String serverUserName = format.getValue("-server", null);
		String serverHost = null;
		if (StrUtil.isNotEmpty(serverUserName)) {
			// 容错：去掉误加的首尾引号，避免主机名带上 " 而无法解析(UnknownHostException)
			serverUserName = StrUtil.stripQuote(serverUserName);
			int a = serverUserName.indexOf("@");
			serverHost = a == -1 ? serverUserName : serverUserName.substring(a + 1);
			serverUserName = a == -1 ? "root" : serverUserName.substring(0, a);
		}
		String serverPassword = format.getValue("-P", null);
		// 服务器地址
		this.socksPort = format.containsKey("-D") ? Integer.parseInt(format.getValue("-D", null)) : -1;
		this.httpPort = format.containsKey("-H") ? Integer.parseInt(format.getValue("-H", null)) : -1;
		this.socksUserName = format.getValue("-suser", null);
		this.socksPassword = format.getValue("-spwd", null);
		// 如果数据没有，就从控制台读取
		MyConsoleCollect console = MyConsoleCollect.createConsoleCollect();
		if (socksPort != -1) {
			if (StrUtil.isNotEmpty(socksUserName) && StrUtil.isNotEmpty(socksPassword)) {
				Logf.printf("socks5认证用户:%s,认证密码:******", socksUserName);
			} else {
				Logf.printf("socks5无授权认证");
			}
		}
		if (httpPort > 0) {
			if (StrUtil.isNotEmpty(socksUserName) && StrUtil.isNotEmpty(socksPassword)) {
				Logf.printf("http代理认证用户:%s,认证密码:******", socksUserName);
			} else {
				Logf.printf("http代理无授权认证");
			}
		}
		List<CopyItem> locals = readScript(format, "-s", "-L");
		List<CopyItem> remotes = readScript(format, "-R");
		if (!locals.isEmpty() || !remotes.isEmpty() || (StrUtil.isNotEmpty(serverHost))) {
			serverHost = console.readConsole("请输入服务器地址:", serverHost, false);
			serverUserName = console.readConsole("请输入服务器账号:", serverUserName, false);
			serverPassword = console.readConsole("请输入服务器密码:", serverPassword, true);
			Logf.printf("用户名:%s,地址:%s,端口:%s", serverUserName, serverHost, serverPort);
		}
		serverPassword = getPassword(serverPassword);
		if ((socksPort > 0 || httpPort > 0) && StrUtil.isNotEmpty(socksUserName) && StrUtil.isNotEmpty(socksPassword)) {
			socksPassword = getPassword(socksPassword);
		}
		if (StrUtil.isNotEmpty(serverHost)) {
			this.sshConfig = new SSHConfig(serverHost, serverUserName, serverPassword, serverPort,
					// 默认 8：多 Session 把通道负载分散到多条 TCP 连接，缓解队头阻塞；
				// 且低于 OpenSSH MaxStartups(10)阈值，避免启动时并发认证被随机丢弃
					format.getInteger("-pool", 8));
			sshConfig.setRemotes(remotes);
			sshConfig.setLocals(locals);
		}
	}

	private String getPassword(String pwd) {
		try {
			for (int i = 0; i < 4; i++) {
				pwd = DES3Util.decode(pwd, SSHConfig.CYPHER_KEY);
			}
		} catch (Exception _) {
		}
		return pwd;
	}

	/**
	 * 需要执行的脚本
	 */
	private List<CopyItem> readScript(ParamFormat paramFormat, String... arr) {
		List<CopyItem> res = new ArrayList<>();
		for (String str : arr) {
			res.addAll(paramFormat.getValueList(str, new LinkedList<>()).stream().map(CopyItem::new)
					.collect(Collectors.toSet()));
		}
		return res;
	}

	public boolean checkWork() {
		if (socksPort > 0 || httpPort > 0) {
			return true;
		}
		if (sshConfig != null) {
			return !sshConfig.getLocals().isEmpty() || !sshConfig.getRemotes().isEmpty();
		}
		return false;
	}
}
