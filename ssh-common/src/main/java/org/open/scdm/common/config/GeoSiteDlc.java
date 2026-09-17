package org.open.scdm.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v2fly domain-list-community 发布的 dlc.dat(geosite 数据)解析器。
 * <p>
 * dlc.dat 是 protobuf 编码的 GeoSiteList，实际用到的 wire 结构：
 * <pre>
 * GeoSiteList{ entry(1,LEN) = GeoSite }          列表条目
 * GeoSite{ country_code(1,STR); domain(2,LEN) }   列表名 / 域名规则
 * Domain{ type(1,VARINT); value(2,STR); attribute(3,LEN，忽略) }
 * </pre>
 * type 与数据源语法对应：0=keyword(子串)、1=regexp(正则)、2=domain(子域匹配)、3=full(完整匹配)，
 * 规则细节见 <a href="https://github.com/v2fly/domain-list-community#structure-of-data">structure of data</a>。
 * <p>
 * 手写最小 wire-format 解析(varint / 长度前缀 / 未知字段跳过)，不引入 protobuf 依赖。
 */
public final class GeoSiteDlc {
	/**
	 * keyword: 规则，主机名包含指定子串
	 */
	public static final int TYPE_KEYWORD = 0;
	/**
	 * regexp: 规则，主机名匹配正则
	 */
	public static final int TYPE_REGEXP = 1;
	/**
	 * domain: 规则，匹配该域名的任意子域(语义同直连主域名)
	 */
	public static final int TYPE_DOMAIN = 2;
	/**
	 * full: 规则，完整域名精确匹配，不含子域
	 */
	public static final int TYPE_FULL = 3;
	/**
	 * GeoSiteList.entry 字段 tag(field 1, wire 2)
	 */
	private static final int ENTRY_TAG = (1 << 3) | 2;
	/**
	 * GeoSite.country_code 字段 tag(field 1, wire 2)
	 */
	private static final int CODE_TAG = (1 << 3) | 2;
	/**
	 * GeoSite.domain 字段 tag(field 2, wire 2)
	 */
	private static final int DOMAIN_TAG = (2 << 3) | 2;
	/**
	 * Domain.type 字段 tag(field 1, wire 0)
	 */
	private static final int TYPE_TAG = (1 << 3) | 0;
	/**
	 * Domain.value 字段 tag(field 2, wire 2)
	 */
	private static final int VALUE_TAG = (2 << 3) | 2;

	/**
	 * 一条域名规则：type 取 TYPE_* 常量，value 为关键字/正则/域名
	 */
	public record DomainRule(int type, String value) {
	}

	private GeoSiteDlc() {
	}

	/**
	 * 解析 dlc.dat，仅收集 names 指定的 geosite 列表(名字忽略大小写)。
	 * 非目标列表只读出列表名即整体跳过，避免全量展开占用内存
	 */
	public static Map<String, List<DomainRule>> parse(InputStream in, Set<String> names) throws IOException {
		Map<String, List<DomainRule>> res = new HashMap<>();
		Set<String> wanted = new HashSet<>();
		if (names != null) {
			for (String name : names) {
				String lower = name.trim().toLowerCase(Locale.ROOT);
				if (!lower.isEmpty()) {
					wanted.add(lower);
				}
			}
		}
		if (wanted.isEmpty()) {
			return res;
		}
		Reader r = new Reader(in.readAllBytes());
		while (r.pos < r.buf.length) {
			int tag = r.varint();
			if (tag == ENTRY_TAG) {
				// 先取 len(推进 pos 到内容头)再算绝对尾，避免 r.pos 先于 r.len() 求值读到旧位置
				int len = r.len();
				parseEntry(r, r.pos + len, wanted, res);
			} else {
				r.skipField(tag);
			}
		}
		return res;
	}

	/**
	 * 解析单个 GeoSite 条目(区间 [pos,end))，非目标列表跳过剩余内容
	 */
	private static void parseEntry(Reader r, int end, Set<String> wanted, Map<String, List<DomainRule>> res)
			throws IOException {
		// dlc.dat 内列表名为全大写(生成器做了 ToUpper)，统一小写后与 wanted 匹配
		String code = null;
		List<DomainRule> rules = null;
		while (r.pos < end) {
			int tag = r.varint();
			// protobuf 按字段号升序输出，country_code 通常先于 domain；
			// 未知顺序时宁可先解析 domain，最终按 code 过滤，保证结果正确
			if (tag == CODE_TAG) {
				code = r.string().toLowerCase(Locale.ROOT);
			} else if (tag == DOMAIN_TAG) {
				if (code != null && !wanted.contains(code)) {
					r.pos = end;
					return;
				}
				// 先取 len(推进 pos 到内容头)再算绝对尾，避免 r.pos 先于 r.len() 求值读到旧位置
				int domainLen = r.len();
				DomainRule rule = parseDomain(r, r.pos + domainLen);
				if (rule != null) {
					if (rules == null) {
						rules = new ArrayList<>();
					}
					rules.add(rule);
				}
			} else {
				r.skipField(tag);
			}
		}
		if (code != null && rules != null && wanted.contains(code)) {
			res.put(code, rules);
		}
	}

	/**
	 * 解析单条 Domain 规则(区间 [pos,end))，空值返回 null
	 */
	private static DomainRule parseDomain(Reader r, int end) throws IOException {
		int type = TYPE_KEYWORD;
		String value = null;
		while (r.pos < end) {
			int tag = r.varint();
			if (tag == TYPE_TAG) {
				type = r.varint();
			} else if (tag == VALUE_TAG) {
				value = r.string();
			} else {
				r.skipField(tag);
			}
		}
		return value == null || value.isEmpty() ? null : new DomainRule(type, value);
	}

	/**
	 * 最小 wire-format 读取器：varint / 长度前缀 / 定长跳过
	 */
	private static final class Reader {
		final byte[] buf;
		int pos;

		Reader(byte[] buf) {
			this.buf = buf;
		}

		int varint() throws IOException {
			int value = 0;
			int shift = 0;
			while (true) {
				if (pos >= buf.length || shift > 28) {
					throw new IOException("非法dlc.dat:varint越界");
				}
				int b = buf[pos++] & 0xFF;
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					return value;
				}
				shift += 7;
			}
		}

		int len() throws IOException {
			int len = varint();
			if (len < 0 || pos + len > buf.length) {
				throw new IOException("非法dlc.dat:长度越界");
			}
			return len;
		}

		String string() throws IOException {
			int len = len();
			String str = new String(buf, pos, len, StandardCharsets.UTF_8);
			pos += len;
			return str;
		}

		void skipField(int tag) throws IOException {
			switch (tag & 7) {
				case 0 -> varint();
				case 1 -> pos += 8;
				// 不能写 pos += len()：复合赋值会先取 pos 旧值(长度字节处)再求 len()，
				// 跳过位置正好少一个长度字节的宽度，后续全部错位
				case 2 -> {
					int len = len();
					pos += len;
				}
				case 5 -> pos += 4;
				// group 类型已废弃，dlc.dat 不会出现，越界容错交由后续读取判断
				default -> {
				}
			}
			if (pos > buf.length) {
				throw new IOException("非法dlc.dat:字段越界");
			}
		}
	}
}
