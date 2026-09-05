package org.open.scdm.common.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.open.scdm.common.vertx.AsynchOutputStream;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

/**
 * 通道工具
 */
public class ChannelUtils {
	/**
	 * 通道建立超时(ms)：服务端需先完成到目标主机的 TCP 连接才回 OPEN_CONFIRMATION，
	 * 高 RTT 链路(客户端→SSH 服务器 + 服务器→目标 两段往返)下 1s 容易误判失败
	 */
	private static final long CHANNEL_CONNECT_TIMEOUT = 10000;

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

	public static void openChannel(String host, Integer port, ClientSession session, NetSocket socket)
			throws IOException {
		openChannel(host, port, session, socket, null);
	}

	/**
	 * 打开 direct-tcpip 通道并双向转发。
	 *
	 * @param initialToTarget 通道连通后先写给目标的字节（如 http 代理改写后的请求报文）；可为 null。
	 *                        在 socket.handler 装配之前同步写入，保证先于后续客户端数据到达目标。
	 */
	public static void openChannel(String host, Integer port, ClientSession session, NetSocket socket,
			byte[] initialToTarget) throws IOException {
		// originator 取代理客户端侧地址，与 ssh direct-tcpip 协议语义一致
		SocketAddress origin = socket.remoteAddress();
		ChannelDirectTcpip targetChannel = session.createDirectTcpipChannel(
				new SshdSocketAddress(origin.host(), origin.port()),
				new SshdSocketAddress(host, port.intValue()));
		// 远端数据由 MINA 会话线程直接推给该流（内部为 Vert.x 异步写，不阻塞 IO 线程）；
		// 通道关闭时 MINA 会 close 该流，进而在 AsynchOutputStream 中关闭本地 socket
		targetChannel.setOut(new AsynchOutputStream(host, port, socket));
		// 通道打开确认即代表远端 TCP 已连通；失败/超时由 verify 抛出异常
		targetChannel.open().verify(CHANNEL_CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
		// 本地→远端：阻塞写，遵守远端流控窗口
		OutputStream stream = targetChannel.getInvertedIn();
		// http 代理普通转发：通道连通后先把改写后的请求写给目标。
		// 此时 socket.handler 尚未装配，客户端数据不会插队，写入顺序有保证。
		if (initialToTarget != null && initialToTarget.length > 0) {
			stream.write(initialToTarget);
			stream.flush();
		}
		// 连接级幂等关闭标志：socket 关闭/异常/写失败可能多次触发 closeChannel，避免重复关闭通道
		AtomicBoolean closed = new AtomicBoolean(false);
		// per-connection 单虚拟线程串行执行器：
		// getInvertedIn().write 是阻塞写（等待 SSH 通道窗口），若直接在 socket.handler（event loop 线程）
		// 中执行，远端慢时会阻塞整个 event loop，拖垮同线程上所有连接。
		// 改用每连接一个虚拟线程串行消费，既保证 TCP 字节流顺序，又不阻塞 event loop。
		ExecutorService writer = Executors.newSingleThreadExecutor(
				Thread.ofVirtual().name("ssh-chan-writer-", 0).factory());
		Runnable closeChannel = () -> {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			writer.shutdownNow();
			targetChannel.close(true);
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
