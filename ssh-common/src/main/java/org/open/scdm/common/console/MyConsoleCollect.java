package org.open.scdm.common.console;

import java.io.Console;
import java.util.Scanner;

import org.open.scdm.common.config.StrUtil;

/**
 * @Title: MyConsoleCollect.java
 * @Description: 控制台信息收集
 * @package priv.dm.console
 * @Author: 方明
 * @Date: 2021年3月4日 上午9:52:27
 */
public abstract class MyConsoleCollect {
	protected MyConsoleCollect() {
	}

	public static final MyConsoleCollect createConsoleCollect() {
		Console console = System.console();
		// JDK 22(JDK-8308591) 起，即使标准流被重定向 System.console() 也会返回非 null，
		// 必须用 isTerminal() 判断是否真的连接在终端上
		if (console == null || !console.isTerminal()) {
			return new ScannerConsoleCollect();
		}
		return new SystemConsoleCollect(console);
	}

	/**
	 * 读取控制台信息
	 */
	public String readConsole(String tips, String def, boolean hide, boolean notNull) {
		String res = def;
		do {
			if (StrUtil.isEmpty(res)) {
				res = readLineString(tips, hide);
				if (res == null) {
					// 输入流已结束(EOF)，返回默认值，避免死循环
					return def;
				}
			} else {
				break;
			}
		} while (notNull);
		return res;
	}

	/**
	 * 读取控制台信息
	 */
	public String readConsole(String tips, String def, boolean hide) {
		return readConsole(tips, def, hide, true);
	}

	/**
	 * 读取控制台信息
	 */
	public String readConsole(String tips, boolean hide) {
		return readConsole(tips, null, hide, true);
	}

	/**
	 * 读取控制台信息
	 */
	public String readConsoleISNull(String tips, String def, boolean hide) {
		return readConsole(tips, def, hide, false);
	}

	protected abstract String readLineString(String tips, boolean hide);

	/**
	 * @Title: SystemConsoleCollect.java
	 * @Description: 系统读取实现
	 * @package priv.dm.console
	 * @Author: 方明
	 * @Date: 2021年3月4日 上午10:07:18
	 */
	static class SystemConsoleCollect extends MyConsoleCollect {
		private final Console console;

		SystemConsoleCollect(Console console) {
			this.console = console;
		}

		@Override
		protected String readLineString(String tips, boolean hide) {
			if (hide) {
				char[] pwd = console.readPassword("%s", tips);
				return pwd == null ? null : new String(pwd);
			} else {
				return console.readLine("%s", tips);
			}
		}

	}

	/**
	 * @Title: ScannerConsoleCollect.java
	 * @Description: 直接读取控制台实现
	 * @package priv.dm.console
	 * @Author: 方明
	 * @Date: 2021年3月4日 上午10:04:49
	 */
	static class ScannerConsoleCollect extends MyConsoleCollect {
		private final Scanner scanner;

		public ScannerConsoleCollect() {
			scanner = new Scanner(System.in);
		}

		@Override
		protected String readLineString(String tips, boolean hide) {
			System.out.println(tips.endsWith(":") || tips.endsWith("：") ? tips : tips + ":");
			return scanner.hasNextLine() ? scanner.nextLine() : null;
		}

	}
}
