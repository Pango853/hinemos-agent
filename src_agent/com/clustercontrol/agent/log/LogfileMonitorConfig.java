/*

 Copyright (C) 2011 NTT DATA Corporation

 This program is free software; you can redistribute it and/or
 Modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation, version 2.

 This program is distributed in the hope that it will be
 useful, but WITHOUT ANY WARRANTY; without even the implied
 warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 PURPOSE.  See the GNU General Public License for more details.

 */

package com.clustercontrol.agent.log;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.util.AgentProperties;

public class LogfileMonitorConfig {

	// ロガー
	private static Log m_log = LogFactory.getLog(LogfileMonitorConfig.class);

	private static final String UNCHANGED_STATS_PERIOD = "monitor.logfile.filter.filesizecheck.period";

	private static final String FIRST_PART_DATA_CHECK_PERIOD = "monitor.logfile.filter.fileheadercheck.period";

	private static final String FIRST_PART_DATA_CHECK_SIZE = "monitor.logfile.filter.fileheadercheck.size";

	private static final String FILE_MAX_SIZE = "monitor.logfile.filter.maxsize";

	private static final String PROGRAM = "monitor.logfile.syslog.program";

	/** ファイル変更チェック期間設定（ミリ秒） */
	protected static int unchangedStatsPeriod = 0;

	/** ファイル変更詳細チェック（冒頭データ比較）期間（ミリ秒） */
	protected static int firstPartDataCheckPeriod = 0;

	/** ファイル変更詳細チェック（冒頭データ比較）サイズ（byte） */
	protected static int firstPartDataCheckSize = 0;

	/** 上限ファイルサイズ設定（byte） */
	protected static long fileMaxSize = 0L;

	protected static final String HINEMOS_LOG_AGENT = "hinemos_log_agent";

	/** ログ先頭に定義するプログラム名 */
	protected static String program = HINEMOS_LOG_AGENT;

	static {
		// ファイル変更チェック期間（秒）
		String sleepInterval = AgentProperties.getProperty(UNCHANGED_STATS_PERIOD, "5");
		m_log.info(UNCHANGED_STATS_PERIOD + " = " + sleepInterval + " sec");
		try {
			unchangedStatsPeriod = Integer.parseInt(sleepInterval) * 1000;
		} catch (NumberFormatException e) {
			m_log.warn("LogfileManager() : " + UNCHANGED_STATS_PERIOD, e);
		} catch (Exception e) {
			m_log.warn("LogfileManager() : " + UNCHANGED_STATS_PERIOD, e);
		}
		m_log.debug(UNCHANGED_STATS_PERIOD + " = " + unchangedStatsPeriod);

		// ファイル変更詳細チェック（冒頭データ比較）期間（秒）
		String firstPartDataCheckPeriodStr = AgentProperties.getProperty(FIRST_PART_DATA_CHECK_PERIOD, "300");
		try {
			firstPartDataCheckPeriod = Integer.parseInt(firstPartDataCheckPeriodStr) * 1000;
		} catch (NumberFormatException e) {
			m_log.warn("LogfileManager() : " + FIRST_PART_DATA_CHECK_PERIOD, e);
		} catch (Exception e) {
			m_log.warn("LogfileManager() : " + FIRST_PART_DATA_CHECK_PERIOD, e);
		}
		m_log.debug(FIRST_PART_DATA_CHECK_PERIOD + " = " + firstPartDataCheckPeriod);

		// ファイル変更詳細チェック（冒頭データ比較）サイズ（byte）
		String firstPartDataCheckSizeStr = AgentProperties.getProperty(FIRST_PART_DATA_CHECK_SIZE, "256");
		try {
			firstPartDataCheckSize = Integer.parseInt(firstPartDataCheckSizeStr);
		} catch (NumberFormatException e) {
			m_log.warn("LogfileManager() : " + FIRST_PART_DATA_CHECK_SIZE, e);
		} catch (Exception e) {
			m_log.warn("LogfileManager() : " + FIRST_PART_DATA_CHECK_SIZE, e);
		}
		m_log.debug(FIRST_PART_DATA_CHECK_SIZE + " = " + firstPartDataCheckSize);

		// 上限ファイルサイズ（byte）
		String fileMaxSizeStr = AgentProperties.getProperty(FILE_MAX_SIZE, "2147483648");
		m_log.info(FILE_MAX_SIZE + " = " + fileMaxSizeStr + " byte");
		try {
			fileMaxSize = Long.parseLong(fileMaxSizeStr);
		} catch (NumberFormatException e) {
			m_log.warn("LogfileManager() : " + FILE_MAX_SIZE, e);
		} catch (Exception e) {
			m_log.warn("LogfileManager() : " + FILE_MAX_SIZE, e);
		}
		m_log.debug(FILE_MAX_SIZE + " = " + fileMaxSize);

		// プログラム名を設定
		program = AgentProperties.getProperty(PROGRAM, HINEMOS_LOG_AGENT);
		if ("".equals(program)) {
			program = HINEMOS_LOG_AGENT;
		}
		m_log.debug(PROGRAM + " = " + program);
	}
}
