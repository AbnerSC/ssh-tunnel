package org.open.scdm.common.vertx;

import java.io.IOException;
import java.io.OutputStream;

import org.open.scdm.common.ssh.Logf;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * 异步输出流
 */
public class AsynchOutputStream extends OutputStream {
	private final NetSocket socket;

	private final String host;

	private final Integer port;

	public AsynchOutputStream(String host, Integer port, NetSocket socket) {
		this.host = host;
		this.port = port;
		this.socket = socket;
	}

	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	public void write(byte[] bytes, int off, int len) throws IOException {
		if (len <= 0) {
			close();
			return;
		}
		// appendBytes(bytes, off, len) 直接按区间拷入 Buffer 内部缓冲，
		// 免去原先 new byte[len] + arraycopy 的整段中间拷贝
		this.socket.write(Buffer.buffer().appendBytes(bytes, off, len));
	}

	public void close() throws IOException {
		this.socket.close();
		Logf.log("连接%s:%s已断开，同时关闭%s", this.host, this.port, this.socket.remoteAddress());
	}
}
