package org.open.scdm.common.ssh;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志工具：经 slf4j/logback 输出到控制台与 logs/ 日志文件
 */
public class Logf {

	private static final Logger logger = LoggerFactory.getLogger(Logf.class);

	@Getter
    private static boolean log = false;

	public static void printf(String format, Object... args) {
		logger.info(String.format(format, args));
	}

	public static void log(String format, Object... args) {
		if (log) {
			logger.info(String.format(format, args));
		}
	}

	public static void setLog(boolean log) {
		Logf.log = log;
	}
}
