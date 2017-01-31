/*

 Copyright (C) 2013 NTT DATA Corporation

 This program is free software; you can redistribute it and/or
 Modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation, version 2.

 This program is distributed in the hope that it will be
 useful, but WITHOUT ANY WARRANTY; without even the implied
 warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 PURPOSE.  See the GNU General Public License for more details.

 */
package com.clustercontrol.agent.util;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.Agent;
import com.clustercontrol.agent.log.LogfileResultForwarder;
import com.clustercontrol.agent.winevent.WinEventResultForwarder;
import com.clustercontrol.bean.HinemosModuleConstant;
import com.clustercontrol.bean.ProcessConstant;
import com.clustercontrol.bean.ValidConstant;
import com.clustercontrol.ws.agent.MessageInfo;
import com.clustercontrol.ws.monitor.MonitorInfo;
import com.clustercontrol.ws.monitor.MonitorStringValueInfo;

public class MonitorStringUtil {

	// ロガー
	private static Log m_log = LogFactory.getLog(MonitorStringUtil.class);

	//オリジナルメッセージのサイズ上限（Byte）
	public static final int _messageLimitLength;
	public static final int _messageLimitLengthDefault = 1024;

	static{
		// 1行のメッセージ上限を定める
		String messageLimitLengthStr = AgentProperties.getProperty("monitor.message.length");
		int messageLimitLength = _messageLimitLengthDefault;
		try {
			messageLimitLength = Integer.parseInt(messageLimitLengthStr);
		} catch (NumberFormatException e) {
			m_log.info("monitor.message.length uses " + _messageLimitLengthDefault + ". (" + messageLimitLengthStr + " is not collect)");
		}
		_messageLimitLength = messageLimitLength;
	}

	/**
	 * 監視文字列を整形する
	 * @param line
	 * @return formatted line
	 */
	public static String formatLine(String line){
		// CR-LFの場合は\rが残ってしまうので、ここで削除する。
		line = line.replace("\r", "");

		// 長さが上限値を超える場合は切り捨てる
		if (line.length() > _messageLimitLength) {
			m_log.info("log line is too long");
			line = line.substring(0, _messageLimitLength);
		}
		return line;
	}

	/**
	 * 監視文字列をパターンマッチし、マネージャに送信する
	 * @param line 監視文字列
	 * @param monitorInfo 監視設定
	 */
	public static void patternMatch(String line, MonitorInfo monitorInfo, String filename) {
		patternMatch(line, monitorInfo, null, filename);
	}

	/**
	 * 監視文字列をパターンマッチし、マネージャに送信する
	 * @param line 監視文字列
	 * @param generationDate 生成日時
	 * @param monitorInfo 監視設定
	 */
	public static void patternMatch(String line, MonitorInfo monitorInfo, Date generationDate, String filename) {
		if (monitorInfo.getCalendar() != null &&
				! CalendarWSUtil.isRun(monitorInfo.getCalendar())) {
			m_log.debug("patternMatch is skipped because of calendar");
			return;
		}

		if (monitorInfo.getMonitorFlg() == ValidConstant.TYPE_INVALID) {
			m_log.debug("patternMatch is skipped because of monitor flg");
			return;
		}

		int order_no = 0;
		for(MonitorStringValueInfo stringInfo : monitorInfo.getStringValueInfo()) {
			++order_no;
			if(m_log.isDebugEnabled()){
				m_log.debug("patternMatch() line = " + line
						+ ", monitorId = " + stringInfo.getMonitorId()
						+ ", orderNo = " + order_no
						+ ", pattern = " + stringInfo.getPattern());
			}
			if (!stringInfo.isValidFlg()) {
				continue;
			}
			String patternText = stringInfo.getPattern();
			String message = line;
			m_log.trace("patternMatch check " + message);

			Pattern pattern = null;
			// 大文字・小文字を区別しない場合
			if(stringInfo.isCaseSensitivityFlg()){
				pattern = Pattern.compile(patternText, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
			}
			// 大文字・小文字を区別する場合
			else{
				pattern = Pattern.compile(patternText, Pattern.DOTALL);
			}
			Matcher matcher = pattern.matcher(line);

			if (matcher.matches()) {
				m_log.debug("patternMatch match " + message);

				// 「処理する」
				if (stringInfo.getProcessType() == ProcessConstant.TYPE_YES) {
					MessageInfo logmsg = new MessageInfo();
					logmsg.setMessage(line);

					if(generationDate != null){
						m_log.debug("patternMatch set generation date : " + generationDate);
						logmsg.setGenerationDate(generationDate.getTime());
					}else{
						logmsg.setGenerationDate(new Date().getTime());
					}
					logmsg.setHostName(Agent.getAgentInfo().getHostname());
					
					if (filename != null) {
						monitorInfo.getLogfileCheckInfo().setLogfile(filename);
					}
					
					if (HinemosModuleConstant.MONITOR_LOGFILE.equals(monitorInfo.getMonitorTypeId())) {
						LogfileResultForwarder.getInstance().add(message, logmsg, monitorInfo, stringInfo);
					} else if (HinemosModuleConstant.MONITOR_WINEVENT.equals(monitorInfo.getMonitorTypeId())) {
						WinEventResultForwarder.getInstance().add(message, logmsg, monitorInfo, stringInfo);
					}
					
					m_log.debug("patternMatch send message : " + message);
					m_log.debug("patternMatch send logmsg message : " + logmsg.getMessage());
					m_log.debug("patternMatch send logmsg generation date : " + new Date(logmsg.getGenerationDate()));
					m_log.debug("patternMatch send logmsg hostname : " + logmsg.getHostName());
				}
				break;
			}
		}
	}

}
