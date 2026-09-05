package org.open.scdm.common.ssh;

import lombok.Getter;

/**
 * 日志工具
 */
public class Logf {

	@Getter
    private static boolean log = false;

	public static void printf(String format, Object... args) {
		System.out.println(String.format(format, args));
	}

	public static void log(String format, Object... args) {
		if (log) {
			System.out.println(String.format(format, args));
		}
	}

	public static void setLog(boolean log) {
		Logf.log = log;
	}
}
