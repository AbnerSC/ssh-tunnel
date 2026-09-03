package org.open.scdm.common.config;

import java.util.LinkedList;
import java.util.List;

import lombok.Data;

@Data
public class SSHConfig {
	/**
	 * 密钥
	 */
	public static final String CYPHER_KEY = "sshCopy4rfv^UGUIYOYR^UIO";
	/**
	 * 地址
	 */
	private String addr;
	/**
	 * 账号
	 */
	private String userName;
	/**
	 * 密码
	 */
	private String password;
	/**
	 * 端口
	 */
	private Integer port;
	/**
	 * 连接池大小
	 */
	private Integer pool;
	/**
	 * 转发到本地
	 */
	private List<CopyItem> locals = new LinkedList<>();
	/**
	 * 转发到远程
	 */
	private List<CopyItem> remotes = new LinkedList<>();

	public SSHConfig(String addr, String userName, String password, Integer port, Integer pool) {
		super();
		this.addr = addr;
		this.userName = userName;
		this.password = password;
		this.port = port;
		this.pool = pool;
	}

	public SSHConfig() {
	}

	public String toScript() {
		return String.format("%s@%s:%d", userName, addr, port);
	}
}
