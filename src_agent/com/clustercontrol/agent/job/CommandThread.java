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

package com.clustercontrol.agent.job;

import java.nio.charset.Charset;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.Agent;
import com.clustercontrol.agent.AgentEndPointWrapper;
import com.clustercontrol.agent.SendQueue;
import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.jobmanagement.bean.RunStatusConstant;
import com.clustercontrol.util.CommandCreator;
import com.clustercontrol.util.CommandExecutor;
import com.clustercontrol.util.CommandExecutor.CommandResult;
import com.clustercontrol.ws.jobmanagement.RunInstructionInfo;
import com.clustercontrol.ws.jobmanagement.RunResultInfo;

/**
 * コマンドを実行するスレッドクラス<BR>
 * 
 * ジョブ実行の際にプロセスを生成して、 終了まで、状態を監視するクラスです。
 * 
 */
public class CommandThread extends AgentThread {

	// ロガー
	static private Log m_log = LogFactory.getLog(CommandThread.class);


	// ジョブ実行結果を受け取る際のエンコーディング
	private String m_inputEncoding = null;

	private Process process = null;
	private CommandExecutor cmdExec = null;
	private RunResultInfo resultInfo;

	/**
	 * デバッグ用メイン処理
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			CommandExecutor executor1 = new CommandExecutor(new String[] { "hostname" });
			//System.out.println(executor1.getResult(executor1.execute()).stdout);
			executor1.addEnvironment("HINEMOS_AGENT_HOME", Agent.getAgentHome());
			executor1.execute();
			System.out.println(executor1.getResult().stdout);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * デバッグ用コンストラクタ
	 */
	public CommandThread() {
		super();
	}

	/**
	 * コンストラクタ
	 * 
	 * @param info
	 *            実行指示
	 * @param sendQueue
	 *            実行応答用メッセージ送信クラス
	 * @param runHistory
	 *            実行履歴
	 */
	public CommandThread(RunInstructionInfo info, SendQueue sendQueue) {
		super(info, sendQueue);

		// ログファイルのエンコーディングを設定
		m_inputEncoding =  AgentProperties.getProperty("job.stream.charset");
		if(m_inputEncoding == null){
			m_inputEncoding = System.getProperty("file.encoding");
		}
		m_log.info("job.stream.charset = " + m_inputEncoding);

		// ---------------------------
		// -- 開始メッセージ送信
		// ---------------------------

		// メッセージ作成
		resultInfo = new RunResultInfo();
		resultInfo.setSessionId(m_info.getSessionId());
		resultInfo.setJobunitId(m_info.getJobunitId());
		resultInfo.setJobId(m_info.getJobId());
		resultInfo.setFacilityId(m_info.getFacilityId());
		resultInfo.setCommand(m_info.getCommand());
		resultInfo.setCommandType(m_info.getCommandType());
		resultInfo.setStopType(m_info.getStopType());
		resultInfo.setStatus(RunStatusConstant.START);
		resultInfo.setTime(new Date().getTime());

		m_log.info("run SessionID=" + m_info.getSessionId() + ", JobID="
				+ m_info.getJobId());

		// Hinemosマネージャに開始メッセージ送信
		/* マネージャに開始メッセージが届く前にジョブのコマンドが実行されることと
		VIPの切り替えが起こった場合に、ジョブが複数のエージェントで起動することを防ぐために
		ジョブの開始報告は同期した動作とする*/
		
		try {
			if (!AgentEndPointWrapper.jobResult(resultInfo)) {
				// ジョブがすでに起動している場合
				m_log.warn("This job already run by other agent. SessionID="+ m_info.getSessionId() + ", JobID=" + m_info.getJobId());
				return;
			}
		} catch (Exception e) {
			m_log.error("CommandThread() : " + e.getMessage(), e);
			return;
		}

		// ---------------------------
		// -- コマンド作成(OSへ渡す形式)
		// ---------------------------
		String[] cmd = null;
		String mode = AgentProperties.getProperty("job.command.mode");
		
		/** 指定されたモードでコマンド生成の処理を切り替える */
		try {
			CommandCreator.PlatformType platform = CommandCreator.convertPlatform(mode);
			cmd = CommandCreator.createCommand(m_info.getUser(), m_info.getCommand(), platform, m_info.getSpecifyUser());
			// ---------------------------
			// -- コマンド実行
			// ---------------------------
			for (int i = 0; i < cmd.length; i++) {
				m_log.info("Command Execute [" + i + "] : " + cmd[i]);
			}
			if (cmd.length == 0) {
				m_log.warn("Command Execute : cmd.length=0");
			}

			cmdExec = new CommandExecutor(cmd, Charset.forName(m_inputEncoding), CommandExecutor._disableTimeout, m_limit_jobmsg);
			// クラウド管理のテンプレート機能で使用する環境変数を追加
			// TODO 将来的にはジョブの中でセットせずに、別の方法で実施する必要がある
			cmdExec.addEnvironment("HINEMOS_AGENT_HOME", Agent.getAgentHome());
			process = cmdExec.execute();

			// 実行履歴に追加
			RunHistoryUtil.addRunHistory(m_info, process);
		} catch (Exception e) {
			// プロセス起動に失敗

			// 実行履歴削除メッセージ送信
			m_log.error("CommandThread() : " + e.getMessage());
			
			// Windows環境において指定したコマンドが存在しない場合のエラーメッセージに文字化けが発生するため、英語表記に置き換える
			String errorMessage = e.getMessage();
			String targetStr = "CreateProcess error=2, "; //Windows環境でコマンドが存在しない場合に出現する文字列
			int ptr = errorMessage.lastIndexOf(targetStr);
			if (ptr != -1) {
				// 指定したコマンドが存在しない場合に、英語表記に置き換える
				errorMessage = errorMessage.substring(0, ptr + targetStr.length()) + "No such file or directory";
			}

			// メッセージ作成
			RunResultInfo runErrorInfo = new RunResultInfo();
			runErrorInfo.setSessionId(m_info.getSessionId());
			runErrorInfo.setJobunitId(m_info.getJobunitId());
			runErrorInfo.setJobId(m_info.getJobId());
			runErrorInfo.setFacilityId(m_info.getFacilityId());
			runErrorInfo.setCommand(m_info.getCommand());
			runErrorInfo.setCommandType(m_info.getCommandType());
			runErrorInfo.setStopType(m_info.getStopType());
			runErrorInfo.setStatus(RunStatusConstant.ERROR);
			runErrorInfo.setTime(new Date().getTime());
			runErrorInfo.setErrorMessage(errorMessage);
			runErrorInfo.setMessage("");
			m_sendQueue.put(runErrorInfo);

			//履歴削除
			RunHistoryUtil.delRunHistory(m_info);

			m_log.info("run end");
			return;
		}

	}

	/**
	 * ジョブ（コマンド・スクリプト）を実行するクラス<BR>
	 * 
	 * ReceiveTopicで受け取ったジョブの指示が実行の場合に このメソッドが実行されます。
	 * 
	 */
	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		m_log.debug("run start");

		if (RunHistoryUtil.findRunHistory(m_info) == null) {
			// コンストラクタで失敗 or プロセス終了
			m_log.info("run() : process is null");
			return;
		}

		// コマンド実行
		CommandResult cmdResult = cmdExec.getResult();

		if (cmdResult.exitCode != null) {

			resultInfo.setStatus(RunStatusConstant.END);
			resultInfo.setEndValue(cmdResult.exitCode);

		} else {

			resultInfo.setStatus(RunStatusConstant.ERROR);

		}

		// 終了を送信
		resultInfo.setTime(new Date().getTime());
		resultInfo.setErrorMessage(cmdResult.stderr);
		resultInfo.setMessage(cmdResult.stdout);
		m_sendQueue.put(resultInfo);

		////実行履歴から削除
		RunHistoryUtil.delRunHistory(m_info);

		m_log.debug("run end");
	}
}
