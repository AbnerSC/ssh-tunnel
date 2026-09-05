package org.open.scdm.http.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.open.scdm.common.ssh.Logf;
import org.open.scdm.common.vertx.VertxUtil;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * http 代理单连接处理：解析客户端首个请求，完成 Basic 认证后，
 * 按 CONNECT（隧道）或绝对 URI（普通 HTTP 转发）派发给 {@link HttpProxyClientConsumer}。
 */
class VertxHttpProxyImpl {
	/**
	 * 请求头上限，超过视为非法连接直接关闭
	 */
	private static final int MAX_HEADER_SIZE = 64 * 1024;
	private static final byte[] CRLF_CRLF = { '\r', '\n', '\r', '\n' };
	private static final byte[] REPLY_ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] REPLY_BAD_GATEWAY = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] REPLY_BAD_REQUEST = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] REPLY_AUTH_REQUIRED = "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"ssh-tunnel\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);

	private final String userName;
	private final String password;
	private final boolean auth;
	private final HttpProxyClientConsumer clientConsumer;
	private final NetSocket usedSocket;
	private Vertx vertx;
	/**
	 * 累积请求头（HTTP 头可能跨多个 TCP 分片到达）
	 */
	private final Buffer pending = Buffer.buffer();
	/**
	 * 是否已派发，避免同一连接被重复解析
	 */
	private boolean dispatched = false;
	/**
	 * 超时关闭
	 */
	private Long delayCloseTimer;
	private final Runnable checkDelayCloseFun = () -> {
		if (delayCloseTimer != null) {
			vertx.cancelTimer(delayCloseTimer);
			delayCloseTimer = null;
		}
	};

	public VertxHttpProxyImpl(boolean auth, String userName, String password, HttpProxyClientConsumer clientConsumer,
			NetSocket usedSocket) {
		this.vertx = VertxUtil.current().getVertx();
		this.auth = auth;
		this.userName = userName;
		this.password = password;
		this.clientConsumer = clientConsumer;
		this.usedSocket = usedSocket;
		usedSocket.handler(this::onData);
		usedSocket.exceptionHandler(this::onException);
		// 到时间还没收到完整请求头就关闭
		delayCloseTimer = vertx.setTimer(5000, (v) -> {
			if (!dispatched) {
				Logf.log("http代理连接长时间未发送请求，主动关闭:%s", usedSocket.remoteAddress());
				usedSocket.close();
			}
		});
		Logf.log("http代理收到连接请求:%s", usedSocket.remoteAddress());
	}

	private void onData(Buffer buf) {
		if (dispatched) {
			return;
		}
		checkDelayCloseFun.run();
		pending.appendBuffer(buf);
		int headerEnd = indexOfHeaderEnd();
		if (headerEnd < 0) {
			if (pending.length() > MAX_HEADER_SIZE) {
				Logf.log("http代理请求头过大，关闭:%s", usedSocket.remoteAddress());
				usedSocket.close();
			}
			return;
		}
		dispatched = true;
		byte[] all = pending.getBytes();
		// headerEnd 指向 \r\n\r\n 起始处，头部内容长度为 headerEnd，其后为 body/隧道残留字节
		byte[] remaining = new byte[all.length - (headerEnd + 4)];
		System.arraycopy(all, headerEnd + 4, remaining, 0, remaining.length);
		String head = new String(all, 0, headerEnd, StandardCharsets.ISO_8859_1);
		// 暂停客户端连接，待转发链路就绪后由 consumer 恢复，避免派发窗口内数据丢失
		usedSocket.pause();
		handleRequest(head, remaining);
	}

	private void handleRequest(String head, byte[] remaining) {
		String[] lines = head.split("\r\n");
		if (lines.length == 0 || lines[0].isEmpty()) {
			writeAndClose(REPLY_BAD_REQUEST);
			return;
		}
		String[] requestLine = lines[0].split(" ");
		if (requestLine.length < 2) {
			writeAndClose(REPLY_BAD_REQUEST);
			return;
		}
		String method = requestLine[0].toUpperCase();
		String uri = requestLine[1];
		String version = requestLine.length > 2 ? requestLine[2] : "HTTP/1.1";
		Map<String, String> headers = parseHeaders(lines);

		// 认证：http 代理使用 Proxy-Authorization: Basic
		if (auth && !checkAuth(headers.get("proxy-authorization"))) {
			Logf.log("http代理认证不通过:%s", usedSocket.remoteAddress());
			writeAndClose(REPLY_AUTH_REQUIRED);
			return;
		}

		if ("CONNECT".equals(method)) {
			handleConnect(uri, remaining);
		} else {
			handleForward(method, uri, version, lines, headers, remaining);
		}
	}

	/**
	 * CONNECT 隧道：多用于 HTTPS，建立到 host:port 的透传连接后回 200
	 */
	private void handleConnect(String uri, byte[] remaining) {
		String[] hostPort = parseHostPort(uri, 443);
		if (hostPort == null) {
			writeAndClose(REPLY_BAD_REQUEST);
			return;
		}
		String host = hostPort[0];
		int port = Integer.parseInt(hostPort[1]);
		Logf.log("http代理 CONNECT 请求 %s:%d", host, port);
		clientConsumer.handle(host, port, remaining, REPLY_ESTABLISHED, REPLY_BAD_GATEWAY, usedSocket);
	}

	/**
	 * 普通 HTTP 转发：客户端发送绝对 URI（http://host/path），改写为源站形式后转发，
	 * 并强制 Connection: close 以保证单请求单连接的正确性。
	 */
	private void handleForward(String method, String uri, String version, String[] lines,
			Map<String, String> headers, byte[] remaining) {
		if (!uri.startsWith("http://")) {
			// 非绝对 URI（源站形式）不应发给代理，视为非法
			writeAndClose(REPLY_BAD_REQUEST);
			return;
		}
		String rest = uri.substring("http://".length());
		int slash = rest.indexOf('/');
		String hostPortStr = slash < 0 ? rest : rest.substring(0, slash);
		String path = slash < 0 ? "/" : rest.substring(slash);
		String[] hostPort = parseHostPort(hostPortStr, 80);
		if (hostPort == null) {
			writeAndClose(REPLY_BAD_REQUEST);
			return;
		}
		String host = hostPort[0];
		int port = Integer.parseInt(hostPort[1]);

		// 重建请求报文：请求行改为源站形式，剔除逐跳/代理专用头，追加 Connection: close
		StringBuilder sb = new StringBuilder();
		sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i];
			int c = line.indexOf(':');
			if (c < 0) {
				continue;
			}
			String key = line.substring(0, c).trim().toLowerCase();
			if (key.equals("proxy-authorization") || key.equals("proxy-connection") || key.equals("connection")) {
				continue;
			}
			sb.append(line).append("\r\n");
		}
		sb.append("Connection: close\r\n");
		sb.append("\r\n");
		byte[] headBytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
		byte[] initialToTarget = new byte[headBytes.length + remaining.length];
		System.arraycopy(headBytes, 0, initialToTarget, 0, headBytes.length);
		System.arraycopy(remaining, 0, initialToTarget, headBytes.length, remaining.length);

		Logf.log("http代理转发请求 %s %s:%d%s", method, host, port, path);
		// 普通转发无需向客户端应答（等待目标响应）；请求报文作为首包写给目标
		clientConsumer.handle(host, port, initialToTarget, null, REPLY_BAD_GATEWAY, usedSocket);
	}

	private Map<String, String> parseHeaders(String[] lines) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i];
			int c = line.indexOf(':');
			if (c < 0) {
				continue;
			}
			headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
		}
		return headers;
	}

	private boolean checkAuth(String proxyAuthorization) {
		if (proxyAuthorization == null || !proxyAuthorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			return false;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(proxyAuthorization.substring(6).trim()),
					StandardCharsets.UTF_8);
			int idx = decoded.indexOf(':');
			if (idx < 0) {
				return false;
			}
			String u = decoded.substring(0, idx);
			String p = decoded.substring(idx + 1);
			return userName.equals(u) && password.equals(p);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * 解析 host:port，兼容 IPv6 字面量 [::1]:port。返回 {host, port}，非法返回 null。
	 */
	private String[] parseHostPort(String hp, int defaultPort) {
		if (hp == null || hp.isEmpty()) {
			return null;
		}
		try {
			if (hp.charAt(0) == '[') {
				int end = hp.indexOf(']');
				if (end < 0) {
					return null;
				}
				String host = hp.substring(1, end);
				int port = defaultPort;
				if (end + 1 < hp.length() && hp.charAt(end + 1) == ':') {
					port = Integer.parseInt(hp.substring(end + 2));
				}
				return new String[] { host, String.valueOf(port) };
			}
			int c = hp.lastIndexOf(':');
			if (c < 0) {
				return new String[] { hp, String.valueOf(defaultPort) };
			}
			String host = hp.substring(0, c);
			int port = Integer.parseInt(hp.substring(c + 1));
			return new String[] { host, String.valueOf(port) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 在累积缓冲中查找头部结束标记 \r\n\r\n 的起始下标，未找到返回 -1
	 */
	private int indexOfHeaderEnd() {
		byte[] bytes = pending.getBytes();
		outer: for (int i = 0; i + 3 < bytes.length; i++) {
			for (int j = 0; j < 4; j++) {
				if (bytes[i + j] != CRLF_CRLF[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private void onException(Throwable e) {
		Logf.log("%s发生异常,%s", usedSocket.remoteAddress(), e);
		usedSocket.close();
	}

	private void writeAndClose(byte[] arr) {
		usedSocket.write(Buffer.buffer(arr)).onComplete((res) -> usedSocket.close());
	}
}
