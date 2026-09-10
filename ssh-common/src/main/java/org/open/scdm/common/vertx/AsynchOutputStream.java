package org.open.scdm.common.vertx;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.open.scdm.common.ssh.Logf;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * 异步输出流
 */
public class AsynchOutputStream extends OutputStream {
	/**
	 * 单连接写积压硬上限(字节)：取 SSH 通道流控窗口(2MB)的 8 倍。
	 * 超过该值说明客户端应用已停读，继续缓冲只会推高内存
	 */
	private static final long MAX_PENDING_BYTES = 16 * 1024 * 1024;
	/**
	 * 写队列持续满的最长时间(ms)：客户端写盘卡顿、TCP 窗口收缩等瞬时停读应远小于该值
	 */
	private static final long STUCK_FULL_TIMEOUT = 30_000L;

	private final NetSocket socket;

	private final String host;

	private final Integer port;
	/**
	 * 已提交给 Vert.x 但尚未写到 socket 的字节数
	 */
	private final AtomicLong pendingBytes = new AtomicLong();
	/**
	 * 连接已判死：后续远端数据直接丢弃，直到通道级联关闭
	 */
	private final AtomicBoolean broken = new AtomicBoolean(false);
	/**
	 * 写队列开始持续满的时刻，0 表示当前不满
	 */
	private volatile long fullSince;

	public AsynchOutputStream(String host, Integer port, NetSocket socket) {
		this.host = host;
		this.port = port;
		this.socket = socket;
		// 默认上限对背压判定过小（瞬时下载高峰即满），与硬上限对齐
		socket.setWriteQueueMaxSize((int) MAX_PENDING_BYTES);
	}

	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	public void write(byte[] bytes, int off, int len) throws IOException {
		if (len <= 0) {
			close();
			return;
		}
		if (broken.get()) {
			// 连接已判死：丢弃残留数据，等待通道关闭
			return;
		}
		// 注意：绝不向上抛 IOException——本流由 MINA 会话 IO 线程调用，
		// 抛出会被 ClientSessionImpl.exceptionCaught 捕获并关闭整个 SSH 会话，
		// 一个慢客户端会拖垮共享会话上的全部连接（历史教训）
		if (socket.writeQueueFull()) {
			long now = System.currentTimeMillis();
			if (fullSince == 0) {
				fullSince = now;
			} else if (now - fullSince >= STUCK_FULL_TIMEOUT) {
				breakConnection("客户端持续未消费");
				return;
			}
		} else {
			fullSince = 0;
		}
		if (pendingBytes.get() + len > MAX_PENDING_BYTES) {
			breakConnection("写积压超过上限");
			return;
		}
		pendingBytes.addAndGet(len);
		// appendBytes(bytes, off, len) 直接按区间拷入 Buffer 内部缓冲，
		// 免去原先 new byte[len] + arraycopy 的整段中间拷贝；
		// 写完成即从积压计数中扣除，已进内核发送缓冲的部分不再占用堆内存
		this.socket.write(Buffer.buffer().appendBytes(bytes, off, len))
				.onComplete((v) -> pendingBytes.addAndGet(-len));
	}

	public void close() throws IOException {
		this.socket.close();
		Logf.log("连接%s:%s已断开，同时关闭%s", this.host, this.port, this.socket.remoteAddress());
	}

	/**
	 * 判定连接已失去消费能力：只关闭本地 socket（closeChannel 会级联关闭对应 SSH 通道），
	 * 不影响同一 SSH 会话上的其他连接
	 */
	private void breakConnection(String reason) {
		if (broken.compareAndSet(false, true)) {
			Logf.log("连接%s:%s%s，断开%s", this.host, this.port, reason, this.socket.remoteAddress());
			socket.close();
		}
	}
}
