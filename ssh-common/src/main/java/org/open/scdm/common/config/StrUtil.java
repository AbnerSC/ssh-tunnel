package org.open.scdm.common.config;

public class StrUtil {
	public static boolean isNotEmpty(String str) {
		return (str != null && str.trim().length() > 0);
	}

	public static boolean isEmpty(String str) {
		return !isNotEmpty(str);
	}

	/**
	 * 去掉字符串首尾空白以及成对包裹的引号(" 或 ')。
	 * 用于容错 docker-compose 列表写法(如 SSH_SERVER="root@1.2.3.4")把引号原样带进值里，
	 * 否则主机名会变成 1.2.3.4" 而无法解析。
	 */
	public static String stripQuote(String str) {
		if (str == null) {
			return null;
		}
		String s = str.trim();
		while (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
				|| (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
			s = s.substring(1, s.length() - 1).trim();
		}
		return s;
	}
}
