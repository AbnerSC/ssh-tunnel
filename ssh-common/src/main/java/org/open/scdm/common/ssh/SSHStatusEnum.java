package org.open.scdm.common.ssh;

/**
 * ssh链接状态
 */
public enum SSHStatusEnum {
	/**
	 * 待链接
	 */
	AWAIT_CONNECT,
	/**
	 * 连接中
	 */
	CONNECTING,
	/**
	 * 已连接
	 */
	CONNECTED,
	/**
	 * 连接错误
	 */
	CONNECT_ERROR
}
