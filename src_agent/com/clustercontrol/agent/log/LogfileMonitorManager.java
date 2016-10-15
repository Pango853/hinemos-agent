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

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.SendQueue;
import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.bean.HinemosModuleConstant;
import com.clustercontrol.bean.ValidConstant;
import com.clustercontrol.ws.agent.OutputBasicInfo;
import com.clustercontrol.ws.monitor.LogfileCheckInfo;
import com.clustercontrol.ws.monitor.MonitorInfo;

/**
 * ログ転送スレッドを管理するクラス<BR>
 * 
 * 転送対象ログファイル情報を受け取り、ログ転送スレッドを制御します。
 * 
 */
public class LogfileMonitorManager {
	private static Log log = LogFactory.getLog(LogfileMonitorManager.class);

	/** ファイルパスとファイルの読み込み状態を保持しているマップ */
	//<monitorId + filePath, logFileMonitor>
	private static ConcurrentHashMap<String, LogfileMonitor> logfileMonitorCache =
			new ConcurrentHashMap<String, LogfileMonitor>();

	/** 読み込み中のファイル正規表現一覧。
	 * ここに含まれていないファイル正規表現　→　途中から読み始める。
	 * ここに含まれているファイル正規表現　→　新規ファイルが生成されたと見なし、最初から読む。
	 */
	private static HashSet<String> lastFilePathPatternSet = new HashSet<String>();
	
	/** 監視項目ごとのディレクトリの存在状態を保持しているマップ。
	 */
	private static ConcurrentHashMap<String, Boolean> directoryExistsMap =
			new ConcurrentHashMap<String, Boolean>();

	/** Queue送信  */
	private static SendQueue sendQueue;

	/** ログファイル監視間隔 */
	private static int runInterval = 10000; // 10sec

	/** 監視可能ファイル数 */
	private static int fileMax = 500;

	/** このエージェントの監視設定 */
	private static ArrayList<MonitorInfo> monitorList = new ArrayList<MonitorInfo>();
	
	/**
	 * コンストラクタ
	 * 
	 * @param ejbConnectionManager EJBコネクション管理
	 * @param sendQueue 監視管理Queue送信
	 * @param props ログ転送エージェントプロパティ
	 */
	public static void setSendQueue(SendQueue sendQueue) {
		LogfileMonitorManager.sendQueue = sendQueue;
	}

	static {
		String key1 = "monitor.logfile.filter.interval";
		try {
			String runIntervalStr = AgentProperties.getProperty(key1, Integer.toString(runInterval));
			runInterval = Integer.parseInt(runIntervalStr);
		} catch (Exception e) {
			log.warn("LogfileThread : " + e.getMessage());
		}
		String key2 = "monitor.logfile.filter.maxfiles";
		try {
			String fileMaxStr = AgentProperties.getProperty(key2, Integer.toString(fileMax));
			fileMax = Integer.parseInt(fileMaxStr);
		} catch (Exception e) {
			log.warn("LogfileThread : " + e.getMessage());
		}
		log.info(key1 + "=" + runInterval + ", " + key2 + "=" + fileMax);
	}

	/**
	 * 監視設定をスレッドに反映します。<BR>
	 * 
	 * @param list 転送対象ログファイル情報一覧
	 */
	public static void setMonitorInfoList(ArrayList<MonitorInfo> monitorList) {
		String fileName = "";
		ConcurrentHashMap<String, Boolean> newDirectoryExistsMap = new ConcurrentHashMap<String, Boolean>();
		for (MonitorInfo info : monitorList) {
			LogfileCheckInfo check = info.getLogfileCheckInfo();
			fileName += "[" + check.getDirectory() + "," + check.getFileName() + "]";
			
			// 監視設定の対象ディレクトリ存在チェック
			String directoryStr = check.getDirectory();
			File directory = new File(directoryStr);
			newDirectoryExistsMap.put(check.getMonitorId(), directory.isDirectory());
			log.debug("setLogfileMonitor() : directoryExistsMap put monitorId = " + check.getMonitorId() + 
					", directoryStr = " + directoryStr +
					", exists = " + directory.isDirectory());
			
		}
		log.info("setLogfileMonitor() : m_monitorList=" + fileName);

		directoryExistsMap = newDirectoryExistsMap;
		LogfileMonitorManager.monitorList = monitorList;
	}

	private static void refresh() {
		HashSet<String> newLogfileMonitorCacheKeySet = new HashSet<String>();

		/*
		 * logfileMonitorはログファイルごとにオブジェクトが生成される。
		 * logfileMonitor.monitorInfoListに監視設定が登録される。
		 * (logfileMonitorとmonitorInfoは1対多の関係)
		 */
		/*
		 * 1. logfileMonitorを生成する。
		 */
		int totalFileNumber = 0;
		log.debug("refresh() : m_monitorList.size=" + monitorList.size());
		HashSet<String> filePathPatternSet = new HashSet<String>();
		for (MonitorInfo monitorInfo : monitorList) {
			if (monitorInfo.getMonitorFlg() == ValidConstant.TYPE_INVALID) {
				continue;
			}
			String monitorId = monitorInfo.getMonitorId();
			String directoryPath = monitorInfo.getLogfileCheckInfo().getDirectory();
			String fileNamePattern = monitorInfo.getLogfileCheckInfo().getFileName();
			String fileEncoding = monitorInfo.getLogfileCheckInfo().getFileEncoding();
			String fileReturnCode = monitorInfo.getLogfileCheckInfo().getFileReturnCode();
			log.debug("refresh() monitorId=" + monitorId +
					", directory=" + directoryPath +
					", filenamePattern=" + fileNamePattern + 
					", fileEncoding=" + fileEncoding +
					", fileReturnCode=" + fileReturnCode);

			// 最初から読むか否か判定する。
			// 既存のファイル正規表現であり、新規ファイルの時は最初から読む。
			boolean readFromTop = true;
			String filePathPattern = directoryPath + "///" + fileNamePattern;
			if (!lastFilePathPatternSet.contains(filePathPattern) || filePathPatternSet.contains(filePathPattern)) {
				readFromTop = false;
			}
			filePathPatternSet.add(filePathPattern);

			Pattern pattern = null;
			try {
				pattern = Pattern.compile(fileNamePattern, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
			} catch (Exception e) {
				log.warn("refresh() : " + e.getMessage());
				continue;
			}

			File directory = new File(directoryPath);
			if (!directory.isDirectory()) {
				log.info("refresh() " + directoryPath + " is not directory");
				directoryExistsMap.put(monitorId, directory.isDirectory());
				continue;
			}
			else {
				// ディレクトリが現在存在しており、かつ前回のチェック時に存在していなかった場合は、
				// そのディレクトリ内のファイルは途中から読む（監視対象のフェイルオーバー対応）
				if(!directoryExistsMap.get(monitorId)) {
					log.info("refresh() new " + directoryPath + " is found");
					readFromTop = false;
				}
				directoryExistsMap.put(monitorId, directory.isDirectory());
			}
			
			for (File file : directory.listFiles()) {
				log.debug("refresh() file=" + file.getName());
				if (!file.isFile()) {
					log.debug(file.getName() + " is not file");
					continue;
				}
				Matcher matcher = pattern.matcher(file.getName());
				if(!matcher.matches()) {
					log.debug("refresh() don't match. filename=" + file.getName() + ", pattern=" + fileNamePattern);
					continue;
				}
				if (++totalFileNumber > fileMax) {
					log.warn("refresh() too many files for logfile. not-monitoring file=" + file.getName());
					continue;
				}
				String filePath = file.getAbsolutePath();
				String cacheKey = monitorId + filePath;
				LogfileMonitor logfileMonitor = logfileMonitorCache.get(cacheKey);
				if(logfileMonitor == null){
					// ファイル監視オブジェクトを生成。
					logfileMonitor = new LogfileMonitor(filePath, readFromTop, fileEncoding, fileReturnCode, monitorInfo);
					logfileMonitorCache.put(cacheKey, logfileMonitor);
				}
				logfileMonitor.setMonitor(monitorInfo);
				if (!logfileMonitor.getFileEncoding().equals(fileEncoding)) {
					logfileMonitor.setFileEncoding(fileEncoding);
				}
				if (!logfileMonitor.getFileReturnCode().equals(fileReturnCode)) {
					logfileMonitor.setFileReturnCode(fileReturnCode);
				}
				
				newLogfileMonitorCacheKeySet.add(cacheKey);
			}
		}

		/*
		 * 2. もう監視対象じゃないログファイルのlogfileMonitorをクリーンしてから、削除
		 */
		Iterator<Entry<String, LogfileMonitor>> it = logfileMonitorCache.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, LogfileMonitor> entry = it.next();
			if (!newLogfileMonitorCacheKeySet.contains(entry.getKey())) {
				entry.getValue().clean();
				it.remove();
			}
		}

		lastFilePathPatternSet = filePathPatternSet;
	}

	public static void start() {
		LogfileThread thread = new LogfileThread();
		thread.setName("LogFileMonitor");
		thread.start();
	}

	private static class LogfileThread extends Thread {
		@Override
		public void run() {
			log.info("run LogfileThread");
			while (true) {
				try {
					refresh();
					for (String filePath : logfileMonitorCache.keySet()) {
						LogfileMonitor logfileMonitor = logfileMonitorCache.get(filePath);
						logfileMonitor.run();
					}
				} catch (Exception e) {
					log.warn("LogfileThread : " + e.getClass().getCanonicalName() + ", " +
							e.getMessage(), e);
				} catch (Throwable e) {
					log.error("LogfileThread : " + e.getClass().getCanonicalName() + ", " +
							e.getMessage(), e);
				}
				try {
					Thread.sleep(runInterval);
				} catch (InterruptedException e) {
					log.info("LogfileThread is Interrupted");
					break;
				}
			}
		}
	}

	/**
	 * 監視管理のJMSに情報を通知します。<BR>
	 * 
	 * @param priority 重要度
	 * @param app アプリケーション
	 * @param msgId メッセージID
	 * @param msg メッセージ
	 * @param msgOrg オリジナルメッセージ
	 */
	public static void sendMessage(String filePath, int priority, String app, String msgId, String msg, String msgOrg, String monitorId) {
		// ログ出力情報
		OutputBasicInfo output = new OutputBasicInfo();
		output.setPluginId(HinemosModuleConstant.MONITOR_LOGFILE);
		output.setPriority(priority);
		output.setApplication(app);
		output.setMessageId(msgId);
		output.setMessage(msg);
		output.setMessageOrg(msgOrg);

		output.setGenerationDate(new Date().getTime());
		output.setMonitorId(monitorId);
		output.setFacilityId(""); // マネージャがセットする。
		output.setScopeText(""); // マネージャがセットする。

		sendQueue.put(output);
	}

	protected static int getRunInterval() {
		return runInterval;
	}
}
