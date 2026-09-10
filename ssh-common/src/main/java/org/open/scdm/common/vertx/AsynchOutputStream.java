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
	/**
	 * 单连接写积压上限(字节)：客户端消费慢时 Vert.x 写队列会无限堆积缓冲导致内存持续增长，
	 * 超过上限即判定对端已失去消费能力，抛异常关闭连接释放缓冲。
	 * 取 2MB 与 SSH 通道流控窗口同量级：正常窗口受限的传输不会触顶。
	 */
	private static final int MAX_WRITE_QUEUE_SIZE = 2 * 1024 * 1024;

	private final NetSocket socket;

	private final String host;

	private final Integer port;

	public AsynchOutputStream(String host, Integer port, NetSocket socket) {
		this.host = host;
		this.port = port;
		this.socket = socket;
		// 默认上限是 Integer.MAX_VALUE（等同无上限），必须显式设置才生效
		socket.setWriteQueueMaxSize(MAX_WRITE_QUEUE_SIZE);
	}

	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	public void write(byte[] bytes, int off, int len) throws IOException {
		if (len <= 0) {
			close();
			return;
		}
		// 写队列已满说明客户端持续不消费：继续缓冲只会推高内存，直接断开该连接
		if (socket.writeQueueFull()) {
			throw new IOException("客户端消费过慢，写积压超限，断开连接:" + socket.remoteAddress());
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
