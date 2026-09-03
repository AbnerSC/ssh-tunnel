package org.open.scdm.common.config;

import lombok.Data;

@Data
public class CopyItem {
	/**
	 * 地址
	 */
	private String host;
	/**
	 * 端口
	 */
	private Integer port;
	/**
	 * 目标端口
	 */
	private Integer targetPort;

	public CopyItem() {
	}

	public CopyItem(String str) {
		String[] arr = str.split(":");
		if (arr.length == 3) {
			splitTarget(arr);
		} else {
			splitTarget(str);
		}
	}

	private void splitTarget(String[] arr) {
		this.targetPort = Integer.parseInt(arr[0]);
		this.host = arr[1];
		this.port = Integer.parseInt(arr[2]);
	}

	private void splitTarget(String str) {
		String[] arr = str.split("-");
		this.targetPort = Integer.parseInt(arr[1]);
		arr = arr[0].split(":");
		this.host = arr[0];
		this.port = Integer.parseInt(arr[1]);
	}

	public String toScrpit() {
		return String.format("地址:%s,端口:%d,目标端口:%d", host, port, targetPort);
	}

}
