package priv.dm.common.console;

import java.io.Console;
import java.util.Scanner;

import priv.dm.common.config.StrUtil;

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
		if (System.console() == null) {
			return new ScannerConsoleCollect();
		}
		return new SystemConsoleCollect();
	}

	/**
	 * 读取控制台信息
	 */
	public String readConsole(String tips, String def, boolean hide, boolean notNull) {
		String res = def;
		do {
			if (StrUtil.isEmpty(res)) {
				res = readLineString(tips, hide);
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

		@Override
		protected String readLineString(String tips, boolean hide) {
			Console console = System.console();
			if (hide) {
				return new String(console.readPassword(tips));
			} else {
				return console.readLine(tips);
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
		private Scanner scanner;

		public ScannerConsoleCollect() {
			scanner = new Scanner(System.in);
		}

		@Override
		protected String readLineString(String tips, boolean hide) {
			System.out.println(tips.endsWith(":") || tips.endsWith("：") ? tips : tips + ":");
			scanner.hasNextLine();
			return scanner.nextLine();
		}

	}
}
