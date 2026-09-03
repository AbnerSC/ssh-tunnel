package org.open.scdm.common.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.open.scdm.common.vertx.AsynchOutputStream;
import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

/**
 * 通道工具
 */
public class ChannelUtils {
	/**
	 * 拼接
	 * 
	 * @param arr
	 * @return
	 */
	public static Buffer joinBuffer(byte[]... arr) {
		int num = 0;
		for (byte[] bs : arr) {
			num += bs.length;
		}
		byte[] res = new byte[num];
		num = 0;
		for (byte[] bs : arr) {
			System.arraycopy(bs, 0, res, num, bs.length);
			num += bs.length;
		}
		return Buffer.buffer(res);
	}

	public static void openChannel(String host, Integer port, Session session, NetSocket socket)
			throws JSchException, IOException {
//		ChannelDirectTCPIP targetChannel = (ChannelDirectTCPIP) session.getStreamForwarder(host, port.intValue());
		ChannelDirectTCPIP targetChannel=(ChannelDirectTCPIP) session.openChannel("direct-tcpip");
		targetChannel.setHost(host);
		targetChannel.setPort(port.intValue());
		SocketAddress address = socket.remoteAddress();
		targetChannel.setOrgIPAddress(address.host());
		targetChannel.setOrgPort(address.port());
		targetChannel.setOutputStream((OutputStream) new AsynchOutputStream(host, port, socket, targetChannel));
		targetChannel.connect(1000);
		if (!targetChannel.isConnected()) {
			throw new RuntimeException("连接失败");
		}
		OutputStream stream = targetChannel.getOutputStream();
		// 连接级幂等关闭标志：socket 关闭/异常/写失败可能多次触发 closeChannel，避免重复 disconnect
		AtomicBoolean closed = new AtomicBoolean(false);
		// per-connection 单虚拟线程串行执行器：
		// JSch 的 stream.write 是阻塞写（等待 SSH 通道窗口），若直接在 socket.handler（event loop 线程）
		// 中执行，远端慢时会阻塞整个 event loop，拖垮同线程上所有连接。
		// 改用每连接一个虚拟线程串行消费，既保证 TCP 字节流顺序，又不阻塞 event loop。
		ExecutorService writer = Executors.newSingleThreadExecutor(
				Thread.ofVirtual().name("ssh-chan-writer-", 0).factory());
		Runnable closeChannel = () -> {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			writer.shutdownNow();
			targetChannel.disconnect();
			Logf.log("连接%s已断开，同时关闭%s:%s", socket.remoteAddress(), host, port);
		};
		socket.handler(buf -> {
			byte[] bytes = buf.getBytes();
			if (bytes.length == 0) {
				closeChannel.run();
				return;
			}
			try {
				writer.execute(() -> {
					try {
						stream.write(bytes);
						stream.flush();
					} catch (IOException e) {
						if (Logf.isLog()) {
							e.printStackTrace();
						}
						closeChannel.run();
					}
				});
			} catch (RejectedExecutionException e) {
				// writer 已关闭，连接正在拆除，忽略
				closeChannel.run();
			}
		});
		socket.closeHandler(v -> closeChannel.run());
		socket.exceptionHandler(v -> closeChannel.run());
	}
}
