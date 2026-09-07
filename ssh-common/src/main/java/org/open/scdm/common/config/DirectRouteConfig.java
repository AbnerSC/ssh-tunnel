package org.open.scdm.common.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.open.scdm.common.ssh.Logf;

/**
 * 直连路由配置：直连 IP（单 IP 与网段）与直连域名（主域名匹配）。
 * <p>
 * 命中任一规则的目标由本地直接访问，不经过远程 SSH 代理。
 * 单个配置项内允许用逗号或空白分隔多个值，也允许重复传入参数累积。
 */
public class DirectRouteConfig {
	/**
	 * IPv4 字面量形状。InetAddress.getByName 对非法主机名会触发 DNS 解析，
	 * 必须先做形状校验，只把形似 IP 的串交给它解析
	 */
	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
	/**
	 * IPv6 字面量形状（仅十六进制与冒号，方括号形式由调用方预先剥除）
	 */
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]+");
	/**
	 * 配置项内多值分隔符（逗号/中文逗号/分号/空白）
	 */
	private static final String SEPARATOR = "[,，;\\s]+";
	/**
	 * 精确 IP（规范化后的字面量）
	 */
	private final Set<String> ips = new HashSet<>();
	/**
	 * 网段（CIDR）
	 */
	private final List<Cidr> cidrs = new ArrayList<>();
	/**
	 * 直连域名（小写、去尾部点）
	 */
	private final Set<String> domains = new HashSet<>();

	public DirectRouteConfig(List<String> directIps, List<String> directDomains) {
		if (directIps != null) {
			for (String item : directIps) {
				for (String part : split(item)) {
					addIp(part);
				}
			}
		}
		if (directDomains != null) {
			for (String item : directDomains) {
				for (String part : split(item)) {
					addDomain(part);
				}
			}
		}
	}

	/**
	 * 目标是否命中直连规则
	 */
	public boolean isDirect(String host) {
		if (StrUtil.isEmpty(host)) {
			return false;
		}
		String str = host.trim();
		InetAddress addr = parseIpLiteral(str);
		if (addr != null) {
			if (ips.contains(addr.getHostAddress())) {
				return true;
			}
			for (Cidr cidr : cidrs) {
				if (cidr.contains(addr)) {
					return true;
				}
			}
			return false;
		}
		// 域名主域匹配：example.com 命中 www.example.com / a.b.example.com，不命中 notexample.com
		String lower = stripTailDot(str.toLowerCase(Locale.ROOT));
		if (domains.contains(lower)) {
			return true;
		}
		int idx;
		while ((idx = lower.indexOf('.')) >= 0) {
			lower = lower.substring(idx + 1);
			if (domains.contains(lower)) {
				return true;
			}
		}
		return false;
	}

	public boolean isEmpty() {
		return ips.isEmpty() && cidrs.isEmpty() && domains.isEmpty();
	}

	/**
	 * IP 规则数量（含网段）
	 */
	public int ipRuleCount() {
		return ips.size() + cidrs.size();
	}

	/**
	 * 域名规则数量
	 */
	public int domainRuleCount() {
		return domains.size();
	}

	private void addIp(String item) {
		String str = StrUtil.stripQuote(item);
		if (StrUtil.isEmpty(str)) {
			return;
		}
		int slash = str.indexOf('/');
		if (slash < 0) {
			InetAddress addr = parseIpLiteral(str);
			if (addr == null) {
				Logf.printf("忽略非法直连IP:%s", str);
				return;
			}
			ips.add(addr.getHostAddress());
			return;
		}
		InetAddress addr = parseIpLiteral(str.substring(0, slash));
		if (addr == null) {
			Logf.printf("忽略非法直连网段:%s", str);
			return;
		}
		int prefix = parsePrefix(str.substring(slash + 1), addr.getAddress().length * 8);
		if (prefix < 0) {
			Logf.printf("忽略非法直连网段:%s", str);
			return;
		}
		cidrs.add(new Cidr(addr.getAddress(), prefix));
	}

	private void addDomain(String item) {
		String str = StrUtil.stripQuote(item);
		if (StrUtil.isEmpty(str)) {
			return;
		}
		String lower = stripTailDot(str.trim().toLowerCase(Locale.ROOT));
		if (lower.isEmpty()) {
			return;
		}
		// 容错：误把 IP 塞进域名配置时按 IP 规则处理，而不是静默失效
		InetAddress addr = parseIpLiteral(lower);
		if (addr != null) {
			ips.add(addr.getHostAddress());
			return;
		}
		if (lower.indexOf('/') >= 0) {
			Logf.printf("忽略非法直连域名:%s", str);
			return;
		}
		domains.add(lower);
	}

	/**
	 * 解析前缀长度：支持 24 与 255.255.255.0 两种写法，非法返回 -1
	 */
	private static int parsePrefix(String mask, int maxBits) {
		try {
			if (mask.indexOf('.') >= 0) {
				// 点分掩码：统计二进制 1 的个数，要求 1 连续在前
				InetAddress netmask = parseIpLiteral(mask);
				if (netmask == null) {
					return -1;
				}
				int prefix = 0;
				boolean zeroSeen = false;
				for (byte b : netmask.getAddress()) {
					for (int i = 7; i >= 0; i--) {
						int bit = (b >> i) & 1;
						if (bit == 1) {
							if (zeroSeen) {
								return -1;
							}
							prefix++;
						} else {
							zeroSeen = true;
						}
					}
				}
				return prefix;
			}
			int prefix = Integer.parseInt(mask);
			return (prefix >= 0 && prefix <= maxBits) ? prefix : -1;
		} catch (NumberFormatException _) {
			return -1;
		}
	}

	/**
	 * 解析 IP 字面量，非字面量返回 null（不触发 DNS 解析）
	 */
	private static InetAddress parseIpLiteral(String str) {
		if (str == null || str.isEmpty()) {
			return null;
		}
		boolean v6 = str.indexOf(':') >= 0;
		if (v6 ? !IPV6.matcher(str).matches() : !IPV4.matcher(str).matches()) {
			return null;
		}
		try {
			return InetAddress.getByName(str);
		} catch (UnknownHostException _) {
			return null;
		}
	}

	private static List<String> split(String item) {
		if (item == null) {
			return List.of();
		}
		String str = StrUtil.stripQuote(item);
		if (StrUtil.isEmpty(str)) {
			return List.of();
		}
		List<String> res = new ArrayList<>();
		for (String part : str.split(SEPARATOR)) {
			if (!part.isEmpty()) {
				res.add(part);
			}
		}
		return res;
	}

	private static String stripTailDot(String str) {
		return str.endsWith(".") ? str.substring(0, str.length() - 1) : str;
	}

	/**
	 * CIDR 网段：网络地址 + 前缀长度
	 */
	private record Cidr(byte[] network, int prefix) {
		boolean contains(InetAddress addr) {
			byte[] bytes = addr.getAddress();
			if (bytes.length != network.length) {
				// IPv4 与 IPv6 规则互不匹配
				return false;
			}
			int full = prefix / 8;
			for (int i = 0; i < full; i++) {
				if (bytes[i] != network[i]) {
					return false;
				}
			}
			int rem = prefix % 8;
			if (rem > 0) {
				int mask = 0xFF << (8 - rem);
				if ((bytes[full] & mask) != (network[full] & mask)) {
					return false;
				}
			}
			return true;
		}
	}
}
