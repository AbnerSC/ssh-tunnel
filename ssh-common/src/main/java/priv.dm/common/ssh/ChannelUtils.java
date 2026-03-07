package priv.dm.common.ssh;

import java.io.IOException;
import java.io.OutputStream;

import priv.dm.common.vertx.AsynchOutputStream;
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
			for (byte b : bs) {
				res[num++] = b;
			}
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
		Runnable closeChannel = () -> {
			targetChannel.disconnect();
			Logf.log("连接%s已断开，同时关闭%s:%s", socket.remoteAddress(), host, port);
		};
		socket.handler(buf -> {
			try {
				byte[] bytes = buf.getBytes();
				if (bytes.length > 0) {
					stream.write(bytes);
					stream.flush();
					return;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			closeChannel.run();
		});
		socket.closeHandler(v -> closeChannel.run());
		socket.exceptionHandler(v -> closeChannel.run());
	}
}
