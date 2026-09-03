package org.open.scdm.common.vertx;

import java.io.IOException;
import java.io.OutputStream;

import org.open.scdm.common.ssh.Logf;
import com.jcraft.jsch.ChannelDirectTCPIP;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * 异步输出流
 */
public class AsynchOutputStream extends OutputStream {
	private NetSocket socket;

	private String host;

	private Integer port;

	public AsynchOutputStream(String host, Integer port, NetSocket socket, ChannelDirectTCPIP channel) {
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
		byte[] body = new byte[len];
		System.arraycopy(bytes, off, body, 0, len);
		this.socket.write(Buffer.buffer(body));
	}

	public void close() throws IOException {
		this.socket.close();
		Logf.log("连接%s:%s已断开，同时关闭%s", this.host, this.port, this.socket.remoteAddress());
	}
}
