package priv.dm.tunnel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import priv.dm.common.config.CopyItem;
import priv.dm.common.config.DES3Util;
import priv.dm.common.config.ParamFormat;
import priv.dm.common.config.SSHConfig;
import priv.dm.common.config.StrUtil;
import priv.dm.common.console.MyConsoleCollect;

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
			int a = serverUserName.indexOf("@");
			serverHost = a == -1 ? serverUserName : serverUserName.substring(a + 1);
			serverUserName = a == -1 ? "root" : serverUserName.substring(0, a);
		}
		String serverPassword = format.getValue("-P", null);
		// 服务器地址
		this.socksPort = format.containsKey("-D") ? Integer.parseInt(format.getValue("-D", null)) : -1;
		this.socksUserName = format.getValue("-suser", null);
		this.socksPassword = format.getValue("-spwd", null);
		// 如果数据没有，就从控制台读取
		MyConsoleCollect console = MyConsoleCollect.createConsoleCollect();
		socksPort = socksPort == null ? -1 : socksPort;
		if (socksPort != -1) {
			if (StrUtil.isNotEmpty(socksUserName) && StrUtil.isNotEmpty(socksPassword)) {
				System.out.println("socks5认证用户:" + socksUserName + ",认证密码:******");
			} else {
				System.out.println("socks5无授权认证");
			}
		}
		List<CopyItem> locals = readScript(format, "-s", "-L");
		List<CopyItem> remotes = readScript(format, "-R");
		remotes = remotes == null ? new ArrayList<>(0) : remotes;
		locals = locals == null ? new ArrayList<>(0) : locals;
		if (locals.size() > 0 || remotes.size() > 0 || (StrUtil.isNotEmpty(serverHost))) {
			serverHost = console.readConsole("请输入服务器地址:", serverHost, false);
			serverUserName = console.readConsole("请输入服务器账号:", serverUserName, false);
			serverPassword = console.readConsole("请输入服务器密码:", serverPassword, true);
			System.out.println(String.format("用户名:%s,地址:%s,端口:%s", serverUserName, serverHost, serverPort));
		}
		serverPassword = getPassword(serverPassword);
		if (socksPort > 0 && StrUtil.isNotEmpty(socksUserName) && StrUtil.isNotEmpty(socksPassword)) {
			socksPassword = getPassword(socksPassword);
		}
		if (StrUtil.isNotEmpty(serverHost)) {
			this.sshConfig = new SSHConfig(serverHost, serverUserName, serverPassword, serverPort,
					format.getInteger("-pool", 5));
			sshConfig.setRemotes(remotes);
			sshConfig.setLocals(locals);
		}
	}

	private String getPassword(String pwd) {
		try {
			for (int i = 0; i < 4; i++) {
				pwd = DES3Util.decode(pwd, SSHConfig.CYPHER_KEY);
			}
		} catch (Exception e) {
		}
		return pwd;
	}

	/**
	 * 需要执行的脚本
	 *
	 * @param paramFormat
	 * @param arr
	 * @return
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
		if (socksPort > 0) {
			return true;
		}
		if (sshConfig != null) {
			return !sshConfig.getLocals().isEmpty() || !sshConfig.getRemotes().isEmpty();
		}
		return false;
	}
}
