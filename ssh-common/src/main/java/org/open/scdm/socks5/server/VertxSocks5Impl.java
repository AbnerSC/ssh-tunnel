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
	 * 请求连接
	 * 
	 * @param addr
	 * @param port
	 * @param hostBytes
	 * @param addrtype
	 */
	private void requestConnect(String addr, int port, byte[] hostBytes, byte addrtype) {
		// 请求日志由 onConnectRequest 统一输出，避免同一请求打两遍
		clientConsumer.handle(addr, port, hostBytes, usedSocket);
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
		byte addrLen = 4;
		int port = readPort(bytes);
		if (addrtype == 3) {// 0x03 域名地址(没有打错，就是没有0x02)，
			addrLen = bytes[4];// 域名地址的第1个字节为域名长度，剩下字节为域名名称字节数组
		} else if (addrtype != 1 && addrtype != 4) {
			usedSocket.close();
			return;
		}
		String remoteAddr;
		if (addrtype == 1) {// 0x01 IP V4地址
			remoteAddr = readIPV4(bytes);
		} else if (addrtype == 4) { // 0x04 IP V6地址
			writeAndClose(new byte[] { 0x05, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 });
			return;// 不支持IP V6
		} else {
			remoteAddr = new String(bytes, 5, addrLen, StandardCharsets.UTF_8);
		}
		// 直接从缓冲区间拷贝（去掉 VER/REP 两字节），免于先整段拷贝再 arraycopy 的双重拷贝
		byte[] hostBytes = buffer.getBytes(2, buffer.length());
		Logf.log("收到代理请求 %s:%d,类型:%s", remoteAddr, port, addrtype == 1 ? "ipv4" : "域名");
		requestConnect(remoteAddr, port, hostBytes, addrtype);
	}

	/**
	 * 认证协商
	 */
	private void onAuth(Buffer buffer) {
		byte[] bytes = buffer.getBytes();
		int index = 1;
		// 账号
		byte len = bytes[index];
		String userName = new String(bytes, ++index, len, StandardCharsets.UTF_8);
		index += len;
		// 密码
		len = bytes[index];
		String password = new String(bytes, ++index, len, StandardCharsets.UTF_8);
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
		if (buffer.length() > 2) {
			byte[] bytes = buffer.getBytes();
			if (bytes[0] != 5) {
				usedSocket.close();
				return;
			}
			if (bytes[bytes.length - 2] == 0) {
				// 如果客户端不支持认证
				writeAndClose(new byte[] { 5, (byte) 0xFF });
			}
			if (bytes[bytes.length - 1] != 2) {
				// 如果用户没填账号密码
				writeAndClose(new byte[] { 5, 0x02 });
				return;
			}
			// 设置账号密码认证
			usedSocket.write(Buffer.buffer(new byte[] { 5, 0x02 })).onSuccess((res) -> {
				readDataHandler = this::onAuth;
			});
		}
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
	 * 解析出端口
	 * 
	 * @param bytes
	 * @return
	 */
	private int readPort(byte[] bytes) {
		int index = bytes.length - 2;
		return (bytes[index++] & 0xff) << 8 | (bytes[index++] & 0xff);
	}
}
