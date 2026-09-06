package org.open.scdm.common.config;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * &#064;Title:  ParamFormat.java
 * &#064;Description:  参数格式化
 * &#064;Date:  2020年9月21日 下午5:58:02
 */
public class ParamFormat {
	private final Map<String, LinkedList<String>> param = new TreeMap<>();

	public ParamFormat(String[] args) {
		boolean insert = false;
		String lastKey = null;
		for (String str : args) {
			if (str.startsWith("-")) {
				if (lastKey != null) {
					param.put(lastKey, null);
				}
				insert = true;
				lastKey = str;
			} else if (insert) {
				LinkedList<String> list = param.get(lastKey);
				if (list == null) {
					list = new LinkedList<>();
				}
				list.add(str);
				param.put(lastKey, list);
				lastKey = null;
			}
		}
		if (lastKey != null) {
			param.put(lastKey, null);
		}
	}

	public final String getValue(String key, String def) {
		String res = getValueAsString(key);
		return res != null ? res : def;
	}

	public final LinkedList<String> getValueList(String key) {
		LinkedList<String> res = param.get(key);
		if (res != null) {
			return res;
		}
		throw new NullPointerException("请补全:" + key);
	}

	public final LinkedList<String> getValueList(String key, LinkedList<String> def) {
		LinkedList<String> res = param.get(key);
		if (res != null) {
			return res;
		}
		return def;
	}

	public final String getValue(String key) {
		String res = getValueAsString(key);
		if (res != null) {
			return res;
		}
		throw new NullPointerException("请补全:" + key);
	}

	public final boolean containsKey(String key) {
		return param.containsKey(key);
	}

	public final Integer getInteger(String key, Integer def) {
		String res = getValue(key, null);
		return res == null ? def : Integer.parseInt(res);
	}

	private String getValueAsString(String key) {
		List<String> list = param.get(key);
		if (list != null && !list.isEmpty()) {
			return list.getFirst();
		}
		return null;
	}
}
