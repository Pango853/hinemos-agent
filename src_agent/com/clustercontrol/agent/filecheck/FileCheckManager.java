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

package com.clustercontrol.agent.filecheck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.bean.ValidConstant;
import com.clustercontrol.ws.jobmanagement.JobFileCheck;

/**
 * ログ転送スレッドを管理するクラス<BR>
 * 
 * 転送対象ログファイル情報を受け取り、ログ転送スレッドを制御します。
 * 
 */
public class FileCheckManager {

	//ロガー
	private static Log m_log = LogFactory.getLog(FileCheckManager.class);

	/** ディレクトリとファイルチェック(ジョブ)の状態を保持しているマップ */
	private static ConcurrentHashMap<String, FileCheck> m_fileCheckCache =
			new ConcurrentHashMap<String, FileCheck>();

	/** ファイルチェック間隔 */
	private static int m_runInterval = 10000; // 10sec

	/**
	 * ログファイル監視設定をスレッドに反映します。<BR>
	 * 
	 * @param list 転送対象ログファイル情報一覧
	 */
	public static void setFileCheck(ArrayList<JobFileCheck> jobFileCheckList) {
		HashMap <String, ArrayList<JobFileCheck>> newJobFileCheckMap =
				new HashMap<String, ArrayList<JobFileCheck>>();

		try {
			String runIntervalStr = AgentProperties.getProperty("job.filecheck.interval",
					Integer.toString(m_runInterval));
			m_runInterval = Integer.parseInt(runIntervalStr);
		} catch (Exception e) {
			m_log.warn("FileCheckThread : " + e.getMessage());
		}
		/*
		 * FileCheckはチェック対象のファイルごとにオブジェクトが生成される。
		 * FileCheck.monitorInfoListに監視設定が登録される。
		 * (FileCheckとmonitorInfoは1対多の関係)
		 */
		/*
		 * 1. FileCheckを生成する。
		 */
		for (JobFileCheck jobFileCheck : jobFileCheckList) {
			if (jobFileCheck.getValid() == ValidConstant.TYPE_INVALID) {
				continue;
			}
			m_log.info("jobFileCheck " + jobFileCheck.getId() + ", " + jobFileCheck.getDirectory());
			String directory = jobFileCheck.getDirectory();

			FileCheck fileCheck = m_fileCheckCache.get(directory);
			if(fileCheck == null){
				// ファイル監視オブジェクトを生成。
				fileCheck = new FileCheck(directory);
				m_fileCheckCache.put(directory, fileCheck);
			}

			ArrayList<JobFileCheck> list = newJobFileCheckMap.get(directory);
			if (list == null){
				list = new ArrayList<JobFileCheck> ();
				newJobFileCheckMap.put(directory, list);
			}
			list.add(jobFileCheck);
		}

		/*
		 * 2. FileCheck.monitorInfoListを登録する。
		 */
		ArrayList<String> noDirectoryList = new ArrayList<String>();
		for (String directory : m_fileCheckCache.keySet()) {
			FileCheck fileCheck = m_fileCheckCache.get(directory);
			ArrayList<JobFileCheck> list = newJobFileCheckMap.get(directory);
			fileCheck.setJobFileCheckList(list);
			Integer size = fileCheck.sizeJobFileCheckList();
			if (size == null || size == 0) {
				noDirectoryList.add(directory);
			}
		}
		// 利用していないものは消す
		for (String directory : noDirectoryList) {
			m_fileCheckCache.remove(directory);
		}
	}

	public void start() {
		m_log.info("start");
		FileCheckThread thread = new FileCheckThread();
		thread.setName("FileCheck");
		thread.start();
	}

	private class FileCheckThread extends Thread {
		@Override
		public void run() {
			m_log.info("run FileCheckThread");
			while (true) {
				try {
					ArrayList<String> delList = new ArrayList<String>();
					for (String directory : m_fileCheckCache.keySet()) {
						FileCheck filecheck = m_fileCheckCache.get(directory);
						if (filecheck.sizeJobFileCheckList() == 0) {
							delList.add(directory);
						} else {
							filecheck.run();
						}
					}
					for (String directory : delList) {
						m_fileCheckCache.remove(directory);
					}
				} catch (Exception e) {
					m_log.warn("FileCheckThread : " + e.getClass().getCanonicalName() + ", " +
							e.getMessage(), e);
				} catch (Throwable e) {
					m_log.error("FileCheckThread : " + e.getClass().getCanonicalName() + ", " +
							e.getMessage(), e);
				}
				try {
					Thread.sleep(m_runInterval);
				} catch (InterruptedException e) {
					m_log.info("FileCheckThread is Interrupted");
					break;
				}
			}
		}
	}
}
