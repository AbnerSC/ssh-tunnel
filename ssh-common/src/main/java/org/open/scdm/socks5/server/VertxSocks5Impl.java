package org.open.scdm.socks5.server;

import java.nio.charset.StandardCharsets;

import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.vertx.VertxUtil;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

class VertxSocks5Impl {
	/**
	 * 账号
	 */
	private String userName;
	/**
	 * 密码
	 */
	private String password;
	/**
	 * 实现
	 */
	private Socks5ClientConsumer clientConsumer;
	/**
	 * 用户连接
	 */
	private NetSocket usedSocket;
	/**
	 * 读取数据
	 */
	private Handler<Buffer> readDataHandler;
	private Vertx vertx;
	/**
	 * 超时关闭
	 */
	private Long delayCloseTimer;
	private Runnable checkDelayCloseFun = () -> {
		if (delayCloseTimer != null) {
			vertx.cancelTimer(delayCloseTimer);
			delayCloseTimer = null;
		}
	};

	public VertxSocks5Impl(boolean auth, String userName, String password, Socks5ClientConsumer clientConsumer,
			NetSocket usedSocket) {
		super();
		vertx = VertxUtil.current().getVertx();
		this.userName = userName;
		this.password = password;
		this.clientConsumer = clientConsumer;
		this.usedSocket = usedSocket;
		this.readDataHandler = auth ? this::onAuthPre : this::onNotAuth;
		usedSocket.handler(buf -> this.readDataHandler.handle(buf));
		usedSocket.exceptionHandler(this::onException);
		// 到时间还没消息就关闭
		delayCloseTimer = vertx.setTimer(5000, (v) -> {
			Logf.log("连接长时间未进行认证，主动关闭:%s", usedSocket.remoteAddress());
			usedSocket.close();
		});
		Logf.log("收到连接请求:%s", usedSocket.remoteAddress());
	}

	/**
	 * 当收到连接请求时
	 * 
	 * @param buffer
	 */
	private void onConnectRequest(Buffer buffer) {
		byte[] bytes = buffer.getBytes();
		if (bytes.length < 7 || bytes[1] != 0x01) {
			// 不支持访问代理
			usedSocket.close();
			return;
		}
		byte addrtype = bytes[3];// ADDRESS_TYPE 目标服务器地址类型
		int reqLen;// 连接请求帧总长(含头)，据此精确解析端口/应答字段
		String remoteAddr;
		if (addrtype == 1) {// 0x01 IP V4地址
			reqLen = 10;
			remoteAddr = readIPV4(bytes);
		} else if (addrtype == 3) {// 0x03 域名地址(没有打错，就是没有0x02)
			int domainLen = bytes[4] & 0xFF;// 域名地址的第1个字节为域名长度
			reqLen = 7 + domainLen;
			if (bytes.length < reqLen) {
				// 域名长度超出当前分片，帧不完整
				usedSocket.close();
				return;
			}
			remoteAddr = new String(bytes, 5, domainLen, StandardCharsets.UTF_8);
		} else if (addrtype == 4) {// 0x04 IP V6地址
			// 原实现对 v6 直接回 0x07 拒绝：Google 系域名普遍双栈，Android 偏好解析 AAAA 后，
			// 表现为「其他上网正常，唯独 Google Play 下载/更新失败」；此处解析后透传给隧道，
			// 由 SSH 服务端按字面地址连接（服务端需具备 v6 出口能力）
			reqLen = 22;
			if (bytes.length < reqLen) {
				usedSocket.close();
				return;
			}
			remoteAddr = readIPV6(bytes);
		} else {
			usedSocket.close();
			return;
		}
		int port = readPort(bytes, reqLen);
		// BND 应答字段取帧内精确切片；原先按整段切片，客户端把连接请求与协议数据
		// 同段粘连发送时会污染应答，且粘连数据被整体丢弃（目标收不到首包，TLS 握手挂死）
		byte[] hostBytes = buffer.getBytes(2, reqLen);
		byte[] initialData = bytes.length > reqLen ? buffer.getBytes(reqLen, buffer.length()) : null;
		Logf.log("收到代理请求 %s:%d,类型:%s", remoteAddr, port,
				addrtype == 1 ? "ipv4" : (addrtype == 4 ? "ipv6" : "域名"));
		clientConsumer.handle(remoteAddr, port, hostBytes, initialData, usedSocket);
	}

	/**
	 * 认证协商
	 */
	private void onAuth(Buffer buffer) {
		byte[] bytes = buffer.getBytes();
		// 报文不完整或版本不符：按认证失败处理，避免数组越界导致连接悬挂
		if (bytes.length < 4 || bytes[0] != 1) {
			writeAndClose(new byte[] { 0x01, 0x01 });
			return;
		}
		int index = 1;
		// 账号（长度用无符号读取，>127 字节的账号/密码原会因符号截断解析错乱）
		int ulen = bytes[index] & 0xFF;
		if (bytes.length < index + 1 + ulen + 1) {
			writeAndClose(new byte[] { 0x01, 0x01 });
			return;
		}
		String userName = new String(bytes, ++index, ulen, StandardCharsets.UTF_8);
		index += ulen;
		// 密码
		int plen = bytes[index] & 0xFF;
		if (bytes.length < index + 1 + plen) {
			writeAndClose(new byte[] { 0x01, 0x01 });
			return;
		}
		String password = new String(bytes, ++index, plen, StandardCharsets.UTF_8);
		Logf.log("收到账号:%s", userName);
		// 验证账号密码
		if (this.userName.equals(userName) && this.password.equals(password)) {
			Logf.log("%s验证通过", userName);
			usedSocket.write(Buffer.buffer(new byte[] { 0x01, 0x00 })).onSuccess((res) -> {
				readDataHandler = this::onConnectRequest;
			});
		} else {
			Logf.log("%s验证不通过", userName);
			writeAndClose(new byte[] { 0x01, 0x01 });
		}
	}

	/**
	 * 认证协商
	 */
	private void onAuthPre(Buffer buffer) {
		checkDelayCloseFun.run();
		if (buffer.length() < 3) {
			return;
		}
		byte[] bytes = buffer.getBytes();
		if (bytes[0] != 5) {
			usedSocket.close();
			return;
		}
		int nmethods = bytes[1] & 0xFF;
		// 方法列表未收全：等下一包再处理，避免误判客户端能力
		if (buffer.length() < 2 + nmethods) {
			return;
		}
		// 按协议逐个扫描客户端支持的方法，而非看末尾字节：
		// [0,2]（无认证+密码）这类列表会被末尾字节误判为不支持认证
		boolean supportsPassword = false;
		for (int i = 0; i < nmethods; i++) {
			if ((bytes[2 + i] & 0xFF) == 2) {
				supportsPassword = true;
				break;
			}
		}
		if (supportsPassword) {
			// 服务器要求认证：即使客户端同时支持无认证，也必须选定账号密码认证
			usedSocket.write(Buffer.buffer(new byte[] { 5, 0x02 })).onSuccess((res) -> {
				readDataHandler = this::onAuth;
			});
			return;
		}
		// 无可用的认证方法（客户端不支持账号密码认证），拒绝
		writeAndClose(new byte[] { 5, (byte) 0xFF });
	}

	private void onNotAuth(Buffer buffer) {
		checkDelayCloseFun.run();
		if (buffer.length() > 2) {
			byte[] bytes = buffer.getBytes();
			if (bytes[0] != 5) {
				usedSocket.close();
				return;
			}
			// 设置不需要认证
			usedSocket.write(Buffer.buffer(new byte[] { 5, 0 })).onSuccess((res) -> {
				readDataHandler = this::onConnectRequest;
			});
		}
	}

	private void onException(Throwable e) {
		Logf.log("%s发生异常,%s", usedSocket.remoteAddress(), e);
		usedSocket.close();
	}

	/**
	 * 读取 IPv6 地址，输出小写压缩形式（如 2001:db8::1），作为 direct-tcpip 的目标主机字面量
	 */
	private String readIPV6(byte[] bytes) {
		byte[] addr = new byte[16];
		System.arraycopy(bytes, 4, addr, 0, 16);
		try {
			return java.net.InetAddress.getByAddress(addr).getHostAddress();
		} catch (java.net.UnknownHostException _) {
			// 16 字节定长解析不会失败，兜底返回非法值由上层按连接失败处理
			return null;
		}
	}

	/**
	 * 读取目标ip
	 * 
	 * @param bytes
	 * @return
	 */
	private String readIPV4(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		int end = 8;
		int index = 4;
		while (index < end) {
			sb.append((int) (bytes[index] & 0xFF));
			index++;
			if (index < end) {
				sb.append('.');
			}
		}
		return sb.toString();
	}

	private void writeAndClose(byte[] arr) {
		usedSocket.write(Buffer.buffer(arr)).onComplete((res) -> usedSocket.close());
	}

	/**
	 * 解析出端口：按连接请求帧的精确长度取值，而非缓冲区末尾
	 * （客户端粘连后续协议数据时，缓冲区末尾不再是端口字段）
	 * 
	 * @param bytes
	 * @param reqLen 连接请求帧总长
	 * @return
	 */
	private int readPort(byte[] bytes, int reqLen) {
		return (bytes[reqLen - 2] & 0xff) << 8 | (bytes[reqLen - 1] & 0xff);
	}
}
