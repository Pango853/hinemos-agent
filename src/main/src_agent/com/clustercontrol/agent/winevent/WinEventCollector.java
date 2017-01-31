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
package com.clustercontrol.agent.winevent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.Agent;
import com.clustercontrol.agent.SendQueue;
import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.agent.util.CalendarWSUtil;
import com.clustercontrol.agent.util.CollectorId;
import com.clustercontrol.agent.util.CollectorTask;
import com.clustercontrol.agent.util.MonitorStringUtil;
import com.clustercontrol.bean.HinemosModuleConstant;
import com.clustercontrol.bean.PluginConstant;
import com.clustercontrol.bean.PriorityConstant;
import com.clustercontrol.bean.ValidConstant;
import com.clustercontrol.fault.HinemosException;
import com.clustercontrol.fault.HinemosUnknown;
import com.clustercontrol.util.CommandCreator;
import com.clustercontrol.util.CommandExecutor;
import com.clustercontrol.util.CommandExecutor.CommandResult;
import com.clustercontrol.util.Messages;
import com.clustercontrol.winevent.bean.WinEventConstant;
import com.clustercontrol.ws.agent.OutputBasicInfo;
import com.clustercontrol.ws.monitor.MonitorInfo;
import com.clustercontrol.ws.monitor.WinEventCheckInfo;

public class WinEventCollector implements CollectorTask, Runnable {

	// ロガー
	private Log m_log = LogFactory.getLog(WinEventCollector.class);
	
	public static final int _collectorType = PluginConstant.TYPE_WINEVENT_MONITOR;

	public static final String runPath = Agent.getAgentHome() + "var\\run\\";
	public static final String PREFIX = "winevent-";
	public static final String POSTFIX_LASTRUN = "-lastrun";
	public static final String POSTFIX_COLLECTOR = "-collector";

	private static final String IGNORE_ERR_CATEGORY = "ObjectNotFound";
	private static final String MESSAGE_ID_ERROR = "001";
	private static final String MESSAGE_ID_WARN = "002";
	
	private static final String WIN2003_VERSION_NUM = "5.2";

	// 関連ファイル
	private String psFileName;
	private String lastrunFileName;

	// スケジューラ
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> future;
	
	// イベントログ監視間隔
	private static final String RUN_INTERVAL_KEY = "monitor.winevent.filter.interval";
	private static long runInterval = 10000;
	
	// イベント取得タイムアウト
	private static final String TIMEOUT_KEY = "monitor.winevent.filter.timeout";
	private static long timeout = 30000;
	
	// イベント実行結果バッファ
	private static final String BUFFER_LENGTH_KEY = "monitor.winevent.buffer";
	private static int bufferLength = 100000;
	
	// ポーリング1回あたりのイベントログの最大取得件数
	private static final String MAX_EVENTS_KEY = "monitor.winevent.maxevents";
	private static final int UNLIMITED_EVENTS = -1;
	private static int maxEvents = 1000;

	// イベント収集モード
	private static final String COLLECT_MODE_KEY = "monitor.winevent.mode";
	private static final String MODE_AUTO = "auto";
	private static final String MODE_GET_WIN_EVENT = "get-winevent";
	private static final String MODE_GET_EVENT_LOG = "get-eventlog";
	private static final String MODE_WEVTUTIL = "wevtutil";
	private static String collectMode = MODE_AUTO;

	// イベントメッセージの文字の一時置換用文字
	private static String RETURN_CHAR_REPLACE_KEY = "monitor.winevent.return.char.replace";
	private static String GT_CHAR_REPLACE_KEY = "monitor.winevent.gt.char.replace";
	private static String LT_CHAR_REPLACE_KEY = "monitor.winevent.lt.char.replace";
	private static final String TMP_RETURN_CODE = "#n;";
	private static final String TMP_GT_CODE = "#gt;";
	private static final String TMP_LT_CODE = "#lt;";
	private static String tmpReturnCode = TMP_RETURN_CODE;	// 改行
	private static String tmpGtCode = TMP_GT_CODE;			// ">"
	private static String tmpLtCode = TMP_LT_CODE;			// "<"
	
	// Windowsイベント監視設定
	private MonitorInfo monitorInfo;

	// Internal通知用send-q
	private SendQueue sendQueue;

	
	private Calendar lastrun = null;
	private static String targetProperty;
	
	// PowerShellの文字コード
	private static String POWERSHELL_CHARSET_KEY = "collector.winevent.charset";
	private final String charset;
	
	public WinEventCollector(final MonitorInfo monitorInfo, SendQueue sendQueue) {
		this.monitorInfo = monitorInfo;
		this.sendQueue = sendQueue;

		psFileName = runPath + PREFIX + monitorInfo.getMonitorId() + POSTFIX_COLLECTOR + ".ps1";
		lastrunFileName = runPath + PREFIX + monitorInfo.getMonitorId() + POSTFIX_LASTRUN;

		// lastrunをファイルから取得する
		lastrun = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(lastrunFileName)));
			lastrun.setTimeInMillis(Long.parseLong(in.readLine()));
			m_log.debug("WinEventCollector() lastrun=" + lastrun);
			
			in.close();
		} catch (Exception e) {
			m_log.debug("WinEventCollector() failed to get lastrun. set current time (lastrun=" + lastrun + ")", e);
		} 
		scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "WinEventCollectorWorker-" + monitorInfo.getMonitorId());
			}
		});
		
		// 実行間隔を設定する
		String runIntervalStr = AgentProperties.getProperty(RUN_INTERVAL_KEY);
		try{
			runInterval = Long.parseLong(runIntervalStr);
		} catch (NumberFormatException e){
			m_log.info("collecor.winevent.interval uses " + runInterval + ". (" + runIntervalStr + " is invalid)");
		}
		
		// タイムアウトを設定する
		String timeoutStr = AgentProperties.getProperty(TIMEOUT_KEY);
		try{
			timeout = Long.parseLong(timeoutStr);
		} catch (NumberFormatException e){
			m_log.info("collecor.winevent.timeout uses " + timeout + ". (" + timeoutStr + " is invalid)");
		}

		// 実行結果(XML)を格納するバッファサイズを設定する
		String bufferLengthStr = AgentProperties.getProperty(BUFFER_LENGTH_KEY);
		try{
			bufferLength = Integer.parseInt(bufferLengthStr);
		} catch (NumberFormatException e){
			m_log.info("collecor.winevent.buffer uses " + bufferLength + ". (" + bufferLengthStr + " is invalid)");
		}
		
		// イベントの最大取得件数を設定する
		String maxEventsStr = AgentProperties.getProperty(MAX_EVENTS_KEY);
		try{
			maxEvents = Integer.parseInt(maxEventsStr);
		} catch (NumberFormatException e){
			m_log.info("collector.winevent.maxevents uses " + maxEvents + ". (" + maxEventsStr + " is invalid)");
		}

		// 収集モードを設定する
		String tmpMode = AgentProperties.getProperty(COLLECT_MODE_KEY);
		if(MODE_AUTO.equals(tmpMode) || MODE_GET_WIN_EVENT.equals(tmpMode) || MODE_GET_EVENT_LOG.equals(tmpMode)  || MODE_WEVTUTIL.equals(tmpMode)){
			collectMode = tmpMode;
		} else {
			m_log.info("collector.winevent.mode uses " + collectMode + ". (" + tmpMode + " is invalid)");
		}

		// イベントメッセージの文字の一時置換用文字を設定する
		tmpReturnCode = AgentProperties.getProperty(RETURN_CHAR_REPLACE_KEY);
		if(tmpReturnCode == null){
			tmpReturnCode = TMP_RETURN_CODE;
			m_log.info("collector.winevent.return.char.replace uses " + tmpReturnCode + ". ");
		}
		tmpGtCode = AgentProperties.getProperty(GT_CHAR_REPLACE_KEY);
		if(tmpGtCode == null){
			tmpGtCode = TMP_GT_CODE;
			m_log.info("collector.winevent.gt.char.replace uses " + tmpGtCode + ". ");
		}
		tmpLtCode = AgentProperties.getProperty(LT_CHAR_REPLACE_KEY);
		if(tmpLtCode == null){
			tmpLtCode = TMP_GT_CODE;
			m_log.info("collector.winevent.lt.char.replace uses " + tmpLtCode + ". ");
		}
		
		// PowerShellの書き出し・実行文字コードを決定する
		String tmpCharset = AgentProperties.getProperty(POWERSHELL_CHARSET_KEY);
		if (tmpCharset == null || tmpCharset.length() == 0) {
			tmpCharset = "MS932";
			m_log.info(POWERSHELL_CHARSET_KEY + " uses " + tmpCharset);
		}
		charset = tmpCharset;
	}
	
	@Override
	public void run() {
		m_log.debug("run WinEventMonitorThread");
		
		try{
			long start = System.currentTimeMillis(); // 計測開始
			
			// 監視設定をもとにPowerShellスクリプトを生成する
			String osVersion = System.getProperty("os.version");
			m_log.debug("OS version : " + osVersion); 
			
			// 収集モードごとに合わせた形式で現在時刻を取得する
			Calendar currentCal = null;
			if(MODE_AUTO.equals(collectMode)){			
			// Windows Server 2003(5.2)の場合
				if(WIN2003_VERSION_NUM.equals(osVersion)){
					currentCal = Calendar.getInstance();	// 現在時刻をlocal timeで取得
				}
				// Windows Server 2008(6.0), Windows Server 2008 R2(6.1), Windows Server 2012(6.2)の場合、
				else{
					currentCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));	// 現在時刻をUTCで取得	
				}
			}
			else if(MODE_WEVTUTIL.equals(collectMode)){
				currentCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));	// 現在時刻をUTCで取得
			}
			else if(MODE_GET_WIN_EVENT.equals(collectMode)){
				currentCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));	// 現在時刻をUTCで取得
			}
			else if(MODE_GET_EVENT_LOG.equals(collectMode)){
				currentCal = Calendar.getInstance();	// 現在時刻をlocal timeで取得
			}
			else {
				m_log.error("unknown collect mode : " + collectMode);
				return;
			}
			
			// 収集モードごとにPowerShellスクリプトを作成してlastrunを更新する
			try{
				// 監視設定無効時はイベントログを取得しない
				if (monitorInfo.getMonitorFlg() == ValidConstant.TYPE_INVALID) {
					m_log.debug("WinEventMonitorThread run is skipped because of monitor flg");
					updateLastRun(currentCal);
					return;
				}
				// カレンダによる非稼動時はイベントログを取得しない
				if (monitorInfo.getCalendar() != null &&
						! CalendarWSUtil.isRun(monitorInfo.getCalendar())) {
					m_log.debug("WinEventMonitorThread run is skipped because of calendar settings");
					updateLastRun(currentCal);
					return;
				}
				
				// 収集モードごとにPowerShellスクリプトを作成する
				if(MODE_AUTO.equals(collectMode)){
					// Windows Server 2003(5.2)の場合
					if(WIN2003_VERSION_NUM.equals(osVersion)){
						createGetEventLogPs(currentCal, lastrun, monitorInfo.getWinEventCheckInfo());
					}
					// Windows Server 2008(6.0), Windows Server 2008 R2(6.1), Windows Server 2012(6.2)の場合、
					else{
					createWevtutilPs(currentCal, lastrun, monitorInfo.getWinEventCheckInfo());
					}
				}
				else if(MODE_WEVTUTIL.equals(collectMode)){
					createWevtutilPs(currentCal, lastrun, monitorInfo.getWinEventCheckInfo());
				}
				else if(MODE_GET_WIN_EVENT.equals(collectMode)){
					createGetWinEventPs(currentCal, lastrun, monitorInfo.getWinEventCheckInfo());
				}
				else if(MODE_GET_EVENT_LOG.equals(collectMode)){
					createGetEventLogPs(currentCal, lastrun, monitorInfo.getWinEventCheckInfo());
				}
				else {
					m_log.error("unknown collect mode : " + collectMode); 
					updateLastRun(currentCal);
					return;
				}
			} catch (Exception e){
				m_log.error("unknown error occurred. ", e); 
				updateLastRun(currentCal);
				return;	
			}
				
			// コマンドを生成する
			String[] command = null;
			String commandGetEvent = "powershell -inputformat none -File \"" + psFileName + "\"";
			try {
				CommandCreator.PlatformType platform = CommandCreator.convertPlatform("windows");
				command = CommandCreator.createCommand(System.getProperty("user.name"), commandGetEvent, platform);

			} catch (HinemosException e) {
				m_log.warn("command creation failure. ", e);
			}
			long commandCreated = System.currentTimeMillis();
			m_log.trace("command creating time : " + (commandCreated - start));

			// コマンドを実行する
			CommandResult ret = null;
			synchronized(WinEventCollector.class){
				try {
					CommandExecutor cmdExecutor = new CommandExecutor(command,
							Charset.forName(charset), timeout, bufferLength);
	
					// 実行結果を取得する
					cmdExecutor.execute();
					ret = cmdExecutor.getResult();
				} catch (HinemosUnknown e) {
					m_log.warn("failed executing command for " + monitorInfo.getMonitorId(), e);
				}
			}

			long commandExecuted = System.currentTimeMillis();
			m_log.trace("command running time : " + (commandExecuted - commandCreated));
			
			// バッファのあふれをチェック
			if(ret.bufferDiscarded){
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.11"), "stdout=" + ret.stdout + ", stderr=" + ret.stderr);
				m_log.error("Discard part of stdout or stderr : stdout=" + ret.stdout + ", stderr=" + ret.stderr);
				updateLastRun(currentCal);
				return;
			}
			
			// 実行結果をStAXでパースできるようにフォーマット
			String formattedStdout = "<Events>" + ret.stdout + "</Events>";
			formattedStdout = formattedStdout.replaceAll("\n", "");
			formattedStdout = formattedStdout.replaceAll("\r", "");
			formattedStdout = formattedStdout.replaceAll(tmpReturnCode + "<", "<");	// wevtutilでタグ間に不要な改行文字が入るのを除去
			
			m_log.debug("stdout(formatted) : " + (ret != null ? formattedStdout : null));

			// 実行結果をパースしてEventLogRecordクラスに格納
			ArrayList<EventLogRecord> eventlogs = parseEventXML(new ByteArrayInputStream(formattedStdout.getBytes()));
			Collections.reverse(eventlogs);
		
			long resultParsed = System.currentTimeMillis();
			m_log.trace("xml parsing time : " + (resultParsed - commandExecuted));

			// 監視設定をもとにパターンマッチし、通知情報をマネージャに送信する
			for(EventLogRecord eventlog : eventlogs){
				m_log.debug("Event : " + eventlog);
				// 監視設定をもとにパターンマッチする
				MonitorStringUtil.patternMatch(MonitorStringUtil.formatLine(eventlog.toString()), monitorInfo, eventlog.getTimeCreated(), null);
			}
			
			long end = System.currentTimeMillis();
			m_log.trace("pattern matching time : " + (end - resultParsed));

			m_log.trace("total running time : " + (end - start));
			
			updateLastRun(currentCal);
			
		} catch (Exception e){
			// ScheduledExecutorServiceはrunにて例外がthrowされると次回以降の定期実行を行わなくなるため、catchする。
			m_log.warn("failed executing monitor for " + monitorInfo.getMonitorId(), e);
			
			//lasetrunは更新しない。
			//何度もExceptionが生じた場合は無限ループとなるが、環境障害 or 不具合の可能性が高いため、勝手にスキップするのはそもそもナンセンス。
		}
	}

	/** 
	* 現在時刻を最終実行日時（lastrun）としてファイルに出力する
	* powershellの実行に失敗した場合はここに到達せず、lastrunを更新しない
	*/
	private void updateLastRun(Calendar currentCal) {
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(lastrunFileName);
			pw.println(currentCal.getTimeInMillis());
		} catch (FileNotFoundException e) {
			m_log.debug("parseEvent() create new lastrun file(" + lastrunFileName + ")");
		} finally {			
			// 現在時刻を前回時刻として格納する
			lastrun = currentCal;
			pw.close();
		}
	}
	
	/**
	 * PowerShellの実行環境としての条件を満たしているかどうかをチェックする
	 * 
	 * @return 環境条件を満たしているかどうか
	 */
	private boolean checkEnvironment(){
		return checkPowerShellEnabled() && checkExecutionPolicy();
	}
	
	/**
	 * PowerShellコマンドが実行可能かをチェックする
	 * 
	 * @return PowerShellコマンドが実行可能かをチェックする
	 */
	private boolean checkPowerShellEnabled(){
		m_log.debug("Checking whether powershell is enabled.");
		String[] command = null;
		String commandCheckEnabled = "powershell -inputformat none -Command \"$PSVersionTable\"";
		try {
			CommandCreator.PlatformType platform = CommandCreator.convertPlatform("windows");
			command = CommandCreator.createCommand(System.getProperty("user.name"), commandCheckEnabled, platform);

			CommandExecutor cmdExecutor = new CommandExecutor(command,
					Charset.forName(charset), timeout, bufferLength);
			cmdExecutor.execute();
			CommandResult ret = cmdExecutor.getResult();
			
			if(ret == null){
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.10"), "Failed to execute command:" + commandCheckEnabled);
				m_log.error("Failed to execute command : " + commandCheckEnabled);
				return false;
			}else if(ret.exitCode.intValue() == 1){
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.10"), "Invalid installation : " + ret.stdout);
				m_log.error("Invalid installation : " + ret.stdout);
				return false;
			}else{
				m_log.debug("Valid installation : " + ret.stdout);
			}
			
			return true;
		} catch (HinemosException e) {
			m_log.warn("command creation failure. ", e);
			return false;
		}
		
	}
	
	/**
	 * PowerShellのExecution Policyが"Unrestricted"または"RemoteSigned"であるかをチェックする
	 * 
	 * @return 環境条件を満たしているかどうか
	 */
	private boolean checkExecutionPolicy(){
		m_log.debug("Checking execution policy.");
		String[] command = null;
		String commandCheckPolicy = "powershell -inputformat none -Command \"Get-ExecutionPolicy\"";
		try {
			CommandCreator.PlatformType platform = CommandCreator.convertPlatform("windows");
			command = CommandCreator.createCommand(System.getProperty("user.name"), commandCheckPolicy, platform);

			CommandExecutor cmdExecutor = new CommandExecutor(command,
					Charset.forName(charset), timeout, bufferLength);
			cmdExecutor.execute();
			CommandResult ret = cmdExecutor.getResult();
			
			if(ret == null){
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.9"), "Failed to execute command:" + commandCheckPolicy);
				m_log.error("Failed to execute command : " + commandCheckPolicy);
				return false;
			}
			
			String policy = ret.stdout.replaceAll("\r\n", "");
	
			if("Unrestricted".equals(policy) || "RemoteSigned".equals(policy)){
				m_log.debug("Valid policy : " + ret.stdout);
			}
			else if("Restricted".equals(policy) || "AllSigned".equals(policy)){
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.9"), "Invalid policy : " + ret.stdout);
				m_log.error("Invalid policy : " + ret.stdout);
				return false;
			}
			else{
				// マネージャにエラー内容を通知する
				sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.9"), "Invalid policy : " + ret.stdout);
				m_log.error("Invalid policy : " + ret.stdout);
				return false;
			}
			
			return true;
		} catch (HinemosException e) {
			// マネージャにエラー内容を通知する
			sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.9"), "command creation failure. : " + e.getMessage());
			m_log.warn("command creation failure. ", e);
			return false;
		}
	}
	
	/**
	 * PowerShellのGet-EventLogコマンドレット(Win2003, Win2008, Win2012対応 / Windows Eventing 6.0非対応)を使用して
	 * イベントオブジェクトをSystem.Diagnostics.EventLogEntryとして取得し、
	 * イベントXMLを生成するためのPowerShellスクリプトファイルを生成する
	 * 
	 * @param currentCal 現在時刻
	 * @param previousCal 前回時刻
	 * @param checkInfo 監視設定
	 */
	private void createGetEventLogPs(Calendar currentCal, Calendar previousCal, WinEventCheckInfo checkInfo){
		m_log.debug("createGetEventLogPs() start creating powershell script");

		// 取得対象時刻の時刻(DateTime変換用)を生成する
		DateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		df.setTimeZone(currentCal.getTimeZone());
		String currentTimestamp = df.format(currentCal.getTime());
		String previousTimestamp = df.format(previousCal.getTime());
		
		// PowerShellスクリプトを生成する
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(psFileName, charset);
			pw.println("$Error.Clear()");

			for(String logName : checkInfo.getLogName()){

				// クエリの生成
				String condition = "";
				
				// ログ名
				condition += " -LogName " + logName;
	
				// ソース
				if(checkInfo.getSource() != null && checkInfo.getSource().size() != 0){
					condition += " -Source ";
					for(String sourceName : checkInfo.getSource()){
						condition += "\"";
						condition += sourceName;
						condition += "\", ";
					}
					condition = condition.replaceFirst(", \\z", "");	// 末尾の","を削除
				}
	
				// レベル
				condition += " -EntryType ";
				condition += checkInfo.isLevelWarning() ? "\"" + WinEventConstant.WARNING_STRING + "\", " : "" ;
				condition += checkInfo.isLevelError() ? "\"" + WinEventConstant.ERROR_STRING + "\", " : "" ;
				if(checkInfo.isLevelInformational()){
					condition += "\"" + WinEventConstant.INFORMATION_STRING + "\", ";
					condition += "\"" + WinEventConstant.AUDIT_FAILURE_STRING_OLD + "\", ";	// 「失敗の監査」も「情報」として扱う
					condition += "\"" + WinEventConstant.AUDIT_SUCCESS_STRING_OLD + "\", ";	// 「成功の監査」も「情報」として扱う
				}
				condition = condition.replaceFirst(", \\z", "");	// 末尾の","を削除
				
				// 最大取得件数の指定（処理速度の観点で必須）
				if(maxEvents != UNLIMITED_EVENTS){
					condition += " -newest " + maxEvents;
				}

				// 出力時刻
				// （オプション"-After", "-Before" は非常に低速のため使用しないこと）
				condition += " | Where-Object{$_.TimeGenerated -ge \"" + previousTimestamp + "\" -and $_.TimeGenerated -lt \"" + currentTimestamp + "\"";
				
				// イベントID
				if(checkInfo.getEventId() != null && checkInfo.getEventId().size() != 0){
					condition += " -and (";
					for(Integer id : checkInfo.getEventId()){
						condition += "$_.EventId -eq " + id + " -or ";
					}
					
					condition = condition.replaceFirst(" -or \\z", "");	// 末尾の"or"を削除
					condition += ") ";
				}
				
				// 「成功の監査」、「失敗の監査」で絞り込む
				if(checkInfo.getKeywords() != null && checkInfo.getKeywords().size() != 0){
					condition += " -and (";
					for(Long keyword : checkInfo.getKeywords()){
						if(WinEventConstant.AUDIT_FAILURE_LONG == keyword.longValue()){
							condition += "$_.EntryType -eq \"" + WinEventConstant.AUDIT_FAILURE_STRING_OLD + "\" -or ";
						}else if(WinEventConstant.AUDIT_SUCCESS_LONG == keyword.longValue()){
							condition += "$_.EntryType -eq \"" + WinEventConstant.AUDIT_SUCCESS_STRING_OLD + "\" -or ";
						}
					}
					condition = condition.replaceFirst(" -or \\z", "");	// 末尾の"or"を削除
					condition += ") ";
				}

				

				condition += "} ";

				pw.println("$eventlogs = Get-EventLog" + condition);
				pw.println("$eventlogs |  ?{$_} | ForEach-Object {");	// ?{$_}はnullの場合falseとなる
				pw.println("$message=$_.Message -replace \"`n\",\"" + tmpReturnCode + "\"; ");	// 改行のエスケープ
				pw.println("$message=$message -replace \"<\",\"" + tmpLtCode + "\"; ");			// "<"のエスケープ
				pw.println("$message=$message -replace \">\",\"" + tmpGtCode + "\"; ");			// ">"のエスケープ
				pw.println("Write-Output "
						+ "\"<Event>"
						+ "<EventID>\"$_.EventId\"</EventID>"
						+ "<Provider Name='\"$_.Source\"' />"
						+ "<EntryType>\"$_.EntryType\"</EntryType>"
						+ "<TimeGenerated SystemTime='\"$_.TimeGenerated.GetDateTimeFormats('u')\"'/>"
						+ "<Channel>" + logName + "</Channel>"
						+ "<Computer>\"$_.MachineName\"</Computer>"
						+ "<Message>\"$message\"</Message>"
						+ "</Event>\"}");
				
				// 取得件数が最大取得件数に到達しているか判定
				if(maxEvents != UNLIMITED_EVENTS){
					pw.println();
					pw.println("if($eventlogs.Count -ge " + maxEvents + "){");
					pw.println("    $message = \"maxEvents=" + maxEvents + ", logName=" + logName + "\";");
					pw.println("    Write-Output \"<MaxEventsWarn>\"$message\"</MaxEventsWarn>\"");
					pw.println("}");
				}
				m_log.debug("createGetEventLogPs() Get-WinEvent condition : " + condition);
			}
			// 汎用エラー処理
			pw.println();
			pw.println("if($Error.Count -ne 0){");
			pw.println("    $Error | ForEach-Object {");
			pw.println("        if($_.CategoryInfo.Category -ne \"" + IGNORE_ERR_CATEGORY + "\"){");
			pw.println("            $message = $_.toString();");
			pw.println("            $message_detail = $_.InvocationInfo.PositionMessage;");
			pw.println("            $message_detail = $message_detail -replace \"`n\",\"" + tmpReturnCode + "\";");	// 改行のエスケープ
			pw.println("            $message_detail = $message_detail -replace \"<\",\"" + tmpLtCode + "\";");	// "<"のエスケープ
			pw.println("            $message_detail = $message_detail -replace \">\",\"" + tmpGtCode + "\";");	// ">"のエスケープ
			pw.println("            Write-Output \"<Error>\"$message$message_detail\"</Error>\"");
			pw.println("        }");
			pw.println("    }");
			pw.println("}");
			
			m_log.debug("createGetEventLogPs() created powershell script");

		} catch (FileNotFoundException e) {
			m_log.debug("createGetEventLogPs() cannot create powershell script", e);
		} catch (UnsupportedEncodingException e) {
			m_log.warn("createGetEventLogPs() : can't create powershell script. invalid charset. charset = " + charset, e);
		} finally {
			pw.close();
		}
	}
	/**
	 * PowerShellのGet-WinEventコマンドレット(Win2008, Win2012対応 / Windows Eventing 6.0対応)を使用して
	 * イベントオブジェクトをSystem.Diagnostics.Eventing.Reader.EventLogRecordとして取得し、
	 * イベントXMLを生成するためのPowerShellスクリプトファイルを生成する
	 * 
	 * @param currentCal 現在時刻
	 * @param previousCal 前回時刻
	 * @param checkInfo 監視設定
	 */
	private void createGetWinEventPs(Calendar currentCal, Calendar previousCal, WinEventCheckInfo checkInfo){
		m_log.debug("createGetWinEventPs() start creating powershell script");

		// 取得対象時刻のUTC文字列を生成する
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		df.setTimeZone(currentCal.getTimeZone());
		String currentTimestamp = df.format(currentCal.getTime());
		String previousTimestamp = df.format(previousCal.getTime());
		
		// PowerShellスクリプトを生成する
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(psFileName, charset);
			pw.println("$eventlogs = @()");
			pw.println("$Error.Clear()");

			for(String logName : checkInfo.getLogName()){

				// クエリの生成
				String condition = "";
				
				// 最大取得件数の指定（任意）
				if(maxEvents != UNLIMITED_EVENTS){
					condition += " -MaxEvents " + maxEvents;
				}
	
				// ログ名
				condition += " -LogName " + logName;
	
				// FilterXPath System ここから
				condition += " -FilterXPath \"*[System[";
				
				// ソース
				if(checkInfo.getSource() != null && checkInfo.getSource().size() != 0){
					condition += "Provider[";
					for(String sourceName : checkInfo.getSource()){
						condition += "@Name='";
						condition += sourceName;
						condition += "' or ";
					}
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += "] and ";
				}
				
				// レベル
				condition += "(";
				condition += checkInfo.isLevelCritical() ? "Level=" + WinEventConstant.CRITICAL_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelWarning() ? "Level=" + WinEventConstant.WARNING_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelVerbose() ? "Level=" + WinEventConstant.VERBOSE_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelError() ? "Level=" + WinEventConstant.ERROR_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelInformational() ? "Level=" + WinEventConstant.INFORMATION_LEVEL0 
						+ " or Level=" + WinEventConstant.INFORMATION_LEVEL4 + " or " : "" ;
				
				condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
				condition += ") and ";
				
				// イベントID
				if(checkInfo.getEventId() != null && checkInfo.getEventId().size() != 0){
					condition += "(";
					for(Integer id : checkInfo.getEventId()){
						condition += "EventID=" + id + " or ";
					}
					
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += ") and ";
				}
	
				// タスクの分類
				if(checkInfo.getCategory() != null && checkInfo.getCategory().size() != 0){
					condition += "(";
					for(Integer category : checkInfo.getCategory()){
						condition += "Task=" + category + " or ";
					}
					
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += ") and ";
				}
				
				// キーワード
				if(checkInfo.getKeywords() != null && checkInfo.getKeywords().size() != 0){
					long targetKeyword = 0l;
					for(Long keyword : checkInfo.getKeywords()){
						targetKeyword += keyword.longValue();
					}
					condition += "(band(Keywords," + targetKeyword + ")) and ";
				}
				
				// 出力時刻
				condition += "TimeCreated[@SystemTime>='"
						+ previousTimestamp
						+ "' and @SystemTime<'"
						+ currentTimestamp + "']";
				
				// FilterXPath System ここまで
				condition += "]]\"";
				
				pw.println("$tmplogs = Get-WinEvent" + condition);
				pw.println("$eventlogs += $tmplogs");
				
				// 取得件数が最大取得件数に到達しているか判定
				if(maxEvents != UNLIMITED_EVENTS){
					pw.println();
					pw.println("if($tmplogs.Count -ge " + maxEvents + "){");
					pw.println("    $message = \"maxEvents=" + maxEvents + ", logName=" + logName + "\";");
					pw.println("    Write-Output \"<MaxEventsWarn>\"$message\"</MaxEventsWarn>\"");
					pw.println("}");
				}

				m_log.debug("createGetWinEventPs() Get-WinEvent condition : " + condition);
			}
			pw.print("$eventlogs | ");
			pw.print("?{$_} | ");	// ?{$_}はnullの場合falseとなる
			pw.println("ForEach-Object {");
			pw.println("$before=\"</Event>\"; ");
			pw.println("$message=$_.Message -replace \"`n\",\"" + tmpReturnCode + "\"; ");	// 改行のエスケープ
			pw.println("$message=$message -replace \"<\",\"" + tmpLtCode + "\"; ");			// "<"のエスケープ
			pw.println("$message=$message -replace \">\",\"" + tmpGtCode + "\"; ");			// ">"のエスケープ
			pw.println("$after=\"<Message>\" + $message + \"</Message>\" + $before; ");		// 変換後のメッセージを独自タグで追加で挿入する
			pw.println("$_.toXml() -replace $before, $after; ");
			pw.println("}");
			
			// 汎用エラー処理
			pw.println();
			pw.println("if($Error.Count -ne 0){");
			pw.println("    $Error | ForEach-Object {");
			pw.println("        if($_.CategoryInfo.Category -ne \"" + IGNORE_ERR_CATEGORY + "\"){");
			pw.println("            $message = $_.toString();");
			pw.println("            $message_detail = $_.InvocationInfo.PositionMessage;");
			pw.println("            $message_detail = $message_detail -replace \"`n\",\"" + tmpReturnCode + "\";");	// 改行のエスケープ
			pw.println("            $message_detail = $message_detail -replace \"<\",\"" + tmpLtCode + "\";");	// "<"のエスケープ
			pw.println("            $message_detail = $message_detail -replace \">\",\"" + tmpGtCode + "\";");	// ">"のエスケープ
			pw.println("            Write-Output \"<Error>\"$message$message_detail\"</Error>\"");
			pw.println("        }");
			pw.println("    }");
			pw.println("}");
			
			m_log.debug("createGetWinEventPs() created powershell script");

		} catch (FileNotFoundException e) {
			m_log.debug("createGetWinEventPs() cannot create powershell script", e);
		} catch (UnsupportedEncodingException e) {
			m_log.warn("createGetWinEventPs() : can't create powershell script. invalid charset. charset = " + charset, e);
		} finally {
			pw.close();
		}
	}
	
	/**
	 * wevtutil.exeコマンド(Win2008, Win2012対応 / Windows Eventing 6.0対応)を使用して
	 * イベントオブジェクトをSystem.Diagnostics.Eventing.Reader.EventLogRecordとして取得し、
	 * イベントXMLを生成するためのPowerShellスクリプトファイルを生成する
	 * 
	 * @param currentCal 現在時刻
	 * @param previousCal 前回時刻
	 * @param checkInfo 監視設定
	 */
	private void createWevtutilPs(Calendar currentCal, Calendar previousCal, WinEventCheckInfo checkInfo){
		m_log.debug("createWevtutilPs() start creating powershell script");

		// 取得対象時刻のUTC文字列を生成する
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		df.setTimeZone(currentCal.getTimeZone());
		String currentTimestamp = df.format(currentCal.getTime());
		String previousTimestamp = df.format(previousCal.getTime());
		
		// PowerShellスクリプトを生成する
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(psFileName, charset);
			pw.println("$Error.Clear()");

			for(String logName : checkInfo.getLogName()){

				// クエリの生成
				String condition = "";
				
				// ログ名
				condition += " qe " + logName;
	

				// 最大取得件数の指定（任意）
				if(maxEvents != UNLIMITED_EVENTS){
					condition += " /c:" + maxEvents;
				}
	
				// /q: System ここから
				condition += "  /q:\"*[System[";
				
				// ソース
				if(checkInfo.getSource() != null && checkInfo.getSource().size() != 0){
					condition += "Provider[";
					for(String sourceName : checkInfo.getSource()){
						condition += "@Name='";
						condition += sourceName;
						condition += "' or ";
					}
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += "] and ";
				}
				
				// レベル
				condition += "(";
				condition += checkInfo.isLevelCritical() ? "Level=" + WinEventConstant.CRITICAL_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelWarning() ? "Level=" + WinEventConstant.WARNING_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelVerbose() ? "Level=" + WinEventConstant.VERBOSE_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelError() ? "Level=" + WinEventConstant.ERROR_LEVEL + " or " : "" ;
				condition += checkInfo.isLevelInformational() ? "Level=" + WinEventConstant.INFORMATION_LEVEL0 
						+ " or Level=" + WinEventConstant.INFORMATION_LEVEL4 + " or " : "" ;
				
				condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
				condition += ") and ";
				
				// イベントID
				if(checkInfo.getEventId() != null && checkInfo.getEventId().size() != 0){
					condition += "(";
					for(Integer id : checkInfo.getEventId()){
						condition += "EventID=" + id + " or ";
					}
					
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += ") and ";
				}
	
				// タスクの分類
				if(checkInfo.getCategory() != null && checkInfo.getCategory().size() != 0){
					condition += "(";
					for(Integer category : checkInfo.getCategory()){
						condition += "Task=" + category + " or ";
					}
					
					condition = condition.replaceFirst(" or \\z", "");	// 末尾の"or"を削除
					condition += ") and ";
				}
				
				// キーワード
				if(checkInfo.getKeywords() != null && checkInfo.getKeywords().size() != 0){
					long targetKeyword = 0l;
					for(Long keyword : checkInfo.getKeywords()){
						targetKeyword += keyword.longValue();
					}
					condition += "(band(Keywords," + targetKeyword + ")) and ";
				}
				
				// 出力時刻
				condition += "TimeCreated[@SystemTime>='"
						+ previousTimestamp
						+ "' and @SystemTime<'"
						+ currentTimestamp + "']";
				
				// /q: System ここまで
				condition += "]]\"";
				
				pw.println("$result = wevtutil.exe /rd /f:RenderedXml /e:root" + condition + " | out-string");
				pw.println("$result=$result -replace \"`n\",\"" + tmpReturnCode + "\"; ");	// 改行のエスケープ
				pw.println("$result=$result -replace \"&lt;\",\"" + tmpLtCode + "\"; ");		// "<"のエスケープ
				pw.println("$result=$result -replace \"&gt;\",\"" + tmpGtCode + "\"; ");		// ">"のエスケープ
				
				// 出力結果がbufferLengthを超えた場合は処理しない
				pw.println("if($result.Length -gt " + bufferLength + "){");
				pw.println("    $len = $result.Length;");
				pw.println("    $message = \"outputLength=$len, bufferLength=" + bufferLength + "\";");
				pw.println("    Write-Output \"<BufferOverRunWarn>$message</BufferOverRunWarn>\"");
				pw.println("}");
				pw.println("else{");
				pw.println("    Write-Output $result");
				pw.println("}");

				m_log.debug("createWevtutilPs() wevtutil.exe condition : " + condition);
			}
			
			// 汎用エラー処理
			pw.println();
			pw.println("if($Error.Count -ne 0){");
			pw.println("    $Error | ForEach-Object {");
			pw.println("        if($_.CategoryInfo.Category -ne \"" + IGNORE_ERR_CATEGORY + "\"){");
			pw.println("            $message = $_.toString();");
			pw.println("            $message_detail = $_.InvocationInfo.PositionMessage;");
			pw.println("            $message_detail = $message_detail -replace \"`n\",\"" + tmpReturnCode + "\";");	// 改行のエスケープ
			pw.println("            $message_detail = $message_detail -replace \"<\",\"" + tmpLtCode + "\";");	// "<"のエスケープ
			pw.println("            $message_detail = $message_detail -replace \">\",\"" + tmpGtCode + "\";");	// ">"のエスケープ
			pw.println("            Write-Output \"<Error>\"$message$message_detail\"</Error>\"");
			pw.println("        }");
			pw.println("    }");
			pw.println("}");
			
			m_log.debug("createWevtutilPs() created powershell script");

		} catch (FileNotFoundException e) {
			m_log.debug("createWevtutilPs() cannot create powershell script", e);
		} catch (UnsupportedEncodingException e) {
			m_log.warn("createWevtutilPs() : can't create powershell script. invalid charset. charset = " + charset, e);
		} finally {
			pw.close();
		}
	}

	
	/**
	 * イベントXMLをStAXでパースしてEventLogRecordのリストに変換する
	 * @param eventXmlStream
	 * @return EventLogRecordのリスト
	 */
	private ArrayList<EventLogRecord> parseEventXML(InputStream eventXmlStream) {
		ArrayList<EventLogRecord> eventlogs = new ArrayList<EventLogRecord>();
		
		try {
			XMLInputFactory xmlif = XMLInputFactory.newInstance();
			/**
			 * OpenJDK7/OracleJDK7にて"]"が2回続くと、以降の文字が複数の要素に分割されてしまい、通常の処理では最初の要素のみ扱うようになる。
			 * サードパーティのXMLパーサでは事象が発生しないため、OpenJDK7/OracleJDK7の実装によるものになるが、仕様/不具合として明確化できない。
			 * 以下関連URLにあるパラメタを使用して、複数要素を合体させて、扱うように対応する
			 * 
			 * 関連URL
			 * http://docs.oracle.com/javase/jp/6/api/javax/xml/stream/XMLStreamReader.html#next()
			 */
			String xmlCoalescingKey = "javax.xml.stream.isCoalescing";// TODO JREが変更された場合は、この変数が変更されていないかチェックすること。
			if(m_log.isDebugEnabled()){
				m_log.debug(xmlCoalescingKey + " = true");
			}
			xmlif.setProperty(xmlCoalescingKey, true); 
			XMLStreamReader xmlr = xmlif.createXMLStreamReader(eventXmlStream);
			
			while (xmlr.hasNext()) {
				switch (xmlr.getEventType()) {
				case XMLStreamConstants.START_ELEMENT:
					m_log.trace("EventType : XMLStreamConstants.START_ELEMENT");

					String localName = xmlr.getLocalName();
					m_log.trace("local name : " + localName);

					if("Event".equals(localName)){
						EventLogRecord eventlog = new EventLogRecord();
						eventlogs.add(eventlog);
						m_log.debug("create new EventLogRecord");
					} else {
						String attrLocalName = null;
						String attrValue = null;
						
						if(xmlr.getAttributeCount() != 0){
							attrLocalName = xmlr.getAttributeLocalName(0);
							attrValue = xmlr.getAttributeValue(0);
							m_log.trace("attribute local name : " + attrLocalName);
							m_log.trace("attribute local value : " + attrValue);
						}
						
						if("Provider".equals(localName)){
							if("Name".equals(attrLocalName)){
								m_log.trace("target value : " + attrValue);
								
								EventLogRecord eventlog = eventlogs.get(eventlogs.size() - 1);;
								eventlog.setProviderName(attrValue);
								m_log.debug("set ProviderName : " + eventlog.getProviderName());
							}
						}
						// Get-WinEvent用/wevtutil.exe用
						else if("TimeCreated".equals(localName) && "SystemTime".equals(attrLocalName)){
							m_log.trace("target value : " + attrValue);
							
							// "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'"とするとSの部分でパースに失敗するため、秒までで切り捨てる。
							String formatedDateString = attrValue.replaceAll("\\..*Z", "");
							m_log.trace("formatted target value : " + formatedDateString);
							DateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
							sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
							
							EventLogRecord eventlog = eventlogs.get(eventlogs.size() - 1);;
							try {
								eventlog.setTimeCreated(sdf.parse(formatedDateString));
							} catch (ParseException e) {
								// do nothing
								m_log.error("set TimeCreated Error", e);
							}
							m_log.debug("set TimeCreated : " + eventlog.getTimeCreated());
						}
						// Get-EventLog用
						if("TimeGenerated".equals(localName) && "SystemTime".equals(attrLocalName)){
							m_log.trace("target value : " + attrValue);
							SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'");

							EventLogRecord eventlog = eventlogs.get(eventlogs.size() - 1);;
							try {
								eventlog.setTimeCreated(sdf.parse(attrValue));
							} catch (ParseException e) {
								// do nothing
								m_log.error("set TimeCreated Error", e);
							}
							m_log.debug("set TimeCreated : " + eventlog.getTimeCreated());
						}
						else{
							targetProperty = localName;
							m_log.trace("target property : " + targetProperty);
						}
					}
					
					break;
				case XMLStreamConstants.SPACE:
				case XMLStreamConstants.CHARACTERS:
					m_log.trace("EventType : XMLStreamConstants.CHARACTERS, length=" + xmlr.getTextLength());

					if(targetProperty != null){
						// 汎用エラー処理
						if("Error".equals(targetProperty)){
							String errorMsg = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
							m_log.debug("powershell execution error message : " + errorMsg);
							
							errorMsg = errorMsg.replaceAll(tmpReturnCode, "\r\n");
							errorMsg = errorMsg.replaceAll(tmpLtCode, "<");
							errorMsg = errorMsg.replaceAll(tmpGtCode, ">");
							m_log.debug("powershell execution error message : " + errorMsg);

							// マネージャにエラー内容を通知する
							sendMessage(PriorityConstant.TYPE_CRITICAL, MESSAGE_ID_ERROR, Messages.getString("message.winevent.8"), errorMsg);
						}
						// 最大取得件数の警告用処理
						else if("MaxEventsWarn".equals(targetProperty)){
							String errorMsg = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
							m_log.debug("powershell max events warning : " + errorMsg);
							
							// マネージャにエラー内容を通知する
							sendMessage(PriorityConstant.TYPE_WARNING, MESSAGE_ID_WARN, Messages.getString("message.winevent.12"), errorMsg);
						}
						// バッファ超過の警告用処理
						else if("BufferOverRunWarn".equals(targetProperty)){
							String errorMsg = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
							m_log.debug("powershell buffer overrun warning : " + errorMsg);
							
							// マネージャにエラー内容を通知する
							sendMessage(PriorityConstant.TYPE_WARNING, MESSAGE_ID_WARN, Messages.getString("message.winevent.11"), errorMsg);
						}
						// 正常系処理
						else {
							try{
								EventLogRecord eventlog = eventlogs.get(eventlogs.size() - 1);;
								if("EventID".equals(targetProperty)){
									eventlog.setId(Integer.parseInt(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength())));
									m_log.debug("set EventID : " + eventlog.getId());
								}
								// Get-WinEvent用/wevtutil.exe用
								else if("Level".equals(targetProperty)){
									if(eventlog.getLevel() == WinEventConstant.UNDEFINED){
										eventlog.setLevel(Integer.parseInt(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength())));
										m_log.debug("set Level : " + eventlog.getLevel());
									}
								}
								// Get-EventLog用
								else if("EntryType".equals(targetProperty)){
									String type = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
									
									if(WinEventConstant.ERROR_STRING.equals(type)){
										eventlog.setLevel(WinEventConstant.ERROR_LEVEL);
										m_log.debug("set Level : " + eventlog.getLevel());
									}
									else if(WinEventConstant.WARNING_STRING.equals(type)){
										eventlog.setLevel(WinEventConstant.WARNING_LEVEL);
										m_log.debug("set Level : " + eventlog.getLevel());
									}
									else if(WinEventConstant.INFORMATION_STRING.equals(type)){
										eventlog.setLevel(WinEventConstant.INFORMATION_LEVEL);
										m_log.debug("set Level : " + eventlog.getLevel());
									}
									else if(WinEventConstant.AUDIT_FAILURE_STRING_OLD.equals(type)){
										eventlog.setKeywords(WinEventConstant.AUDIT_FAILURE_LONG);
										m_log.debug("set Keyword : " + eventlog.getKeywords());
									}
									else if(WinEventConstant.AUDIT_SUCCESS_STRING_OLD.equals(type)){
										eventlog.setKeywords(WinEventConstant.AUDIT_SUCCESS_LONG);
										m_log.debug("set Keyword : " + eventlog.getKeywords());
									}
								}
								else if("Task".equals(targetProperty)){
									if(eventlog.getTask() == WinEventConstant.UNDEFINED){
										eventlog.setTask(Integer.parseInt(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength())));
										m_log.debug("set Task : " + eventlog.getTask());
									}
								}
								else if("Keywords".equals(targetProperty)){
									// TODO パースに失敗するのでいったん外す（例：0x8080000000000000）
									//eventlog.setKeywords(Long.decode(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength())));
									//m_log.debug("set Keywords : " + eventlog.getKeywords());
								}
								else if("EventRecordId".equals(targetProperty)){
									eventlog.setRecordId(Long.parseLong(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength())));
									m_log.debug("set RecordId : " + eventlog.getRecordId());
								}
								else if("Channel".equals(targetProperty)){
									eventlog.setLogName(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength()));
									m_log.debug("set LogName : " + eventlog.getLogName());
								}
								else if("Computer".equals(targetProperty)){
									eventlog.setMachineName(new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength()));
									m_log.debug("set MachineName : " + eventlog.getMachineName());
								}
								else if("Message".equals(targetProperty)){
									String message = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
									message = message.replaceAll(tmpReturnCode, "\r\n");
									message = message.replaceAll(tmpLtCode, "<");
									message = message.replaceAll(tmpGtCode, ">");
									eventlog.setMessage(message);
									m_log.debug("set Message : " + eventlog.getMessage());
								}
								else if("Data".equals(targetProperty)){
									String data = new String(xmlr.getTextCharacters(), xmlr.getTextStart(), xmlr.getTextLength());
									eventlog.getData().add(data);
									m_log.debug("set Data : " + data);
								}
								else {
									m_log.debug("unknown target property : " + targetProperty);
								}
							} catch (NumberFormatException e){
								m_log.debug("number parse error", e);
							}
						}
						targetProperty = null;
					}
					break;
				}
				xmlr.next();
			}
			xmlr.close();
		} catch (XMLStreamException e) {
			m_log.warn("parseEvent() xmlstream error", e);
		}
		
		return eventlogs;
		
	}

	/**
	 * 通知をマネージャに送信する。
	 * @param priority
	 * @param msgId
	 * @param message
	 * @param messageOrg
	 */
	private void sendMessage(int priority, String msgId, String message, String messageOrg) {
		OutputBasicInfo output = new OutputBasicInfo();
		output.setPluginId(HinemosModuleConstant.MONITOR_WINEVENT);
		output.setPriority(priority);
		output.setApplication(Messages.getString("agent"));
		output.setMessageId(msgId);
		output.setMessage(message);
		output.setMessageOrg(messageOrg);
		output.setGenerationDate(new Date().getTime());
		output.setMonitorId(monitorInfo.getMonitorId());
		output.setFacilityId(""); // マネージャがセットする。
		output.setScopeText(""); // マネージャがセットする。
		
		sendQueue.put(output);
	}
	
	public MonitorInfo getMonitorInfo() {
		return monitorInfo;
	}

	public void setMonitorInfo(MonitorInfo monitorInfo) {
		this.monitorInfo = monitorInfo;
	}


	@Override
	public CollectorId getCollectorId() {
		return new CollectorId(_collectorType, monitorInfo.getMonitorId());
	}

	/**
	 * タスクを開始(再開)する。 タスクを固定周期(runInterval)で実行します。
	 */
	@Override
	public void start() {
		System.out.println("> start called.");

		// 環境をチェックする
		if(! checkEnvironment()){
			m_log.debug("Cannot get events on this environment. Please check environmental settings. ");
			return;
		}
		future = scheduler.scheduleAtFixedRate(this, 0, runInterval,
				TimeUnit.MILLISECONDS);
	}

	/**
	 * schedulerに登録したタスクを停止する。
	 */
	public void stop() {
		System.out.println("> stop called.");
		if (future != null) {
			future.cancel(true);
		}
	}

	/**
	 * schedulerをシャットダウンする
	 */
	@Override
	public void shutdown() {
		System.out.println("> shutdown called.");
		scheduler.shutdown();
		try {
			scheduler.awaitTermination(30000L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			m_log.warn("shutdown interrupted.", e);
		}
	}


	@Override
	public void update(CollectorTask task) {
		// TODO Auto-generated method stub
		
	}
	
	

}
