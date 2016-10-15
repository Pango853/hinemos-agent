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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.util.MonitorStringUtil;
import com.clustercontrol.agent.util.RandomAccessFileWrapper;
import com.clustercontrol.bean.PriorityConstant;
import com.clustercontrol.util.Messages;
import com.clustercontrol.ws.monitor.MonitorInfo;

/**
 * ログファイル監視<BR>
 */
public class LogfileMonitor {

	// Syslog転送用ロガー
	private LoggerSyslog m_syslog = null;

	// ロガー
	private static Log m_log = LogFactory.getLog(LogfileMonitor.class);

	private static final String MESSAGE_ID_INFO = "001";
	private static final String MESSAGE_ID_WARNING = "002";
	// private static final String MESSAGE_ID_CRITICAL = "003";
	// private static final String MESSAGE_ID_UNKNOWN = "004";

	/** ログファイルのオープンに失敗したときは、最初のみ「ファイルがありません」というinternalイベントを発生させる。 */
	private boolean m_initFlag = true;
	private boolean m_firstOpenStatus = false;	// ファイルを一度でもオープンしたかどうか
	private long m_lastDataCheck = System.currentTimeMillis(); // 最終詳細チェック（冒頭データ比較）実行時刻
	private boolean m_readTopFlag = true;

	private String m_filePath;
	private String m_fileEncoding;
	private String m_fileReturnCode;
	private RandomAccessFileWrapper m_fr = null;
	private long m_filesize = 0;
	private long n_unchanged_stats = 0; // ファイルチェック時に、ファイルに変更がなかった回数
	private byte[] m_carryOverBuf = new byte[0]; // 文字化け対策用に繰り越すバッファ

	private MonitorInfo m_monitorInfo = null;

	private char m_lineSeparator;
	private String m_lineSeparatorString;


	/**
	 * コンストラクタ
	 * 
	 * @param path
	 *            転送対象ログファイル
	 * @param readTopFlag
	 *            最初にファイルをチェック
	 *  @param fileEncoding
	 *  		　ログファイルエンコーディング
	 */
	public LogfileMonitor(String path, boolean readTopFlag, String fileEncoding, String fileReturnCode, MonitorInfo monitorInfo) {
		m_filePath = path;
		m_fileEncoding = fileEncoding;
		m_readTopFlag = readTopFlag;
		m_firstOpenStatus = false;
		m_syslog = new LoggerSyslog();
		m_monitorInfo = monitorInfo;

		m_fileReturnCode = fileReturnCode;
		
		if("CR".equals(m_fileReturnCode)){
			m_lineSeparator = '\r';
			m_lineSeparatorString = "\\r";
		}else{
			m_lineSeparator = '\n';
			m_lineSeparatorString = "\\n";
		}
	}

	public String getFileEncoding() {
		return m_fileEncoding;
	}
	
	public void setFileEncoding(String fileEncoding) {
		this.m_fileEncoding = fileEncoding;
	}

	public String getFileReturnCode() {
		return m_fileReturnCode;
	}

	public void setFileReturnCode(String fileReturnCode) {
		this.m_fileReturnCode = fileReturnCode;
	}

	public void setMonitor(MonitorInfo monitorInfo) {
		this.m_monitorInfo = monitorInfo;
	}

	public void clean() {
		m_log.info("clean " + m_filePath);
		closeFile(m_fr);
	}

	/**
	 * スレッドの動作メソッド<BR>
	 * 
	 */
	public void run() {
		m_log.debug("monitor start.  logfile : " + m_filePath
				+ "  syslog encoding : " + System.getProperty("file.encoding"));
		// ファイルオープン
		File file = new File(m_filePath);

		if (m_fr == null) {
			if (m_initFlag) {
				// 初回読み込みでreadTopFlagがtrueの時は最初から読む。
				if (m_readTopFlag) {
					m_log.info("run() : openFile=" + file + ", init=true, readTopFlag=" + m_readTopFlag);
					m_fr = openFile(file, false); // 最初から読む
				} else {
					m_log.info("run() : openFile=" + file + ", init=true, readTopFlag=" + m_readTopFlag);
					m_fr = openFile(file, true); // 末尾から読む
				}
			} else {
				if (m_firstOpenStatus){
					// firstOpenStatusフラグがtrueかつFileNotFoundが前回おきた場合は先頭から読む
					m_log.info("run() : openFile=" + file + ", init=false");
					m_log.debug("run() : m_initFlag=" + m_initFlag);
					m_log.debug("run() : m_firstOpenStatus=" + m_firstOpenStatus);
					m_fr = openFile(file, false); // 最初から読む
				} else {
					m_fr = openFile(file, true); // 末尾から読む
				}
			}

			// オープンできないと終了
			if (m_fr == null) {
				return;
			}
		}

		boolean readSuccessFlg = true; // 増加分読み込み成功フラグ
		long tmp_filesize = 0;

		try {
			tmp_filesize = m_fr.length(); // 現在監視しているファイルのサイズを取得・・・（１）

			if (m_filesize == tmp_filesize) {
				/** ログローテートを判定するフラグ */
				boolean logrotateFlag = false;			// ローテートフラグ

				int runInterval = LogfileMonitorManager.getRunInterval();
				// ファイルサイズがm_unchangedStatsPeriod秒間以上変わらなかったら、ファイル切り替わりチェック
				if ((++n_unchanged_stats * runInterval) < LogfileMonitorConfig.unchangedStatsPeriod) {
					return;
				}
				m_log.debug("run() : " + m_filePath + " check log rotation");

				// ログローテートされているかチェックする
				// 従来の判定ロジック：
				// 現在監視しているファイルのサイズと本来監視すべきファイルのサイズが異なっている場合、
				// mv方式によりローテートされたと判断する。
				if (m_fr.length() != file.length()) { // ・・・（２）
					m_log.debug("run() : " + m_filePath + " file size not match");
					logrotateFlag = true;

					m_log.debug("run() : m_logrotate set true .1");// rmしたとき、このルートでrotateしたと判断される！！
					m_log.debug("run() : m_fr.length()=" + m_fr.length());
					m_log.debug("run() : file.length()=" + file.length());

				} else if (tmp_filesize > 0 && LogfileMonitorConfig.firstPartDataCheckPeriod > 0 &&
						(System.currentTimeMillis() - m_lastDataCheck) > LogfileMonitorConfig.firstPartDataCheckPeriod){
					m_log.debug("run() : " + m_filePath + " check first part of file");

					// 追加された判定ロジック：
					// 現在監視しているファイルのサイズと本来監視すべきファイルのサイズが同じであっても、
					// mv方式によりローテートされた可能性がある。
					// ファイルの冒頭部分を確認することで、ローテートされたことを確認する。
					byte[] refFirstPartOfFile = new byte[LogfileMonitorConfig.firstPartDataCheckSize];
					Arrays.fill(refFirstPartOfFile, (byte)0);  // 全ての要素を0で初期化
					byte[] newFirstPartOfFile = new byte[LogfileMonitorConfig.firstPartDataCheckSize];
					Arrays.fill(newFirstPartOfFile, (byte)0);  // 全ての要素を0で初期化

					// ファイル名指定で新たにオープンするファイル
					RandomAccessFileWrapper newFile = null;

					// 現在監視しているファイルの冒頭データを取得
					long bufSeek = 0;
					try {
						bufSeek = m_fr.getFilePointer();

						// ファイル冒頭に移動する
						m_fr.seek(0);
						if (m_fr.read(refFirstPartOfFile) > 0) {
							if(m_log.isDebugEnabled()){
								try {
									m_log.debug("run() : " + m_filePath + " refFirstPartOfFile : "
											+ new String(refFirstPartOfFile,
													0,
													tmp_filesize < refFirstPartOfFile.length ? (int)tmp_filesize : refFirstPartOfFile.length,
															this.m_fileEncoding));
								} catch (Exception e) {
									m_log.error("run() : " + m_filePath + " " + e.getMessage(), e);
								}
							}

							// 再度ファイル名指定でファイルを開く
							newFile = new RandomAccessFileWrapper(m_filePath, "r");
							if (newFile.read(newFirstPartOfFile) > 0) {
								if(m_log.isDebugEnabled()){
									try {
										m_log.debug("run() : " + m_filePath + " newFirstPartOfFile : "
												+ new String(newFirstPartOfFile,
														0,
														tmp_filesize < newFirstPartOfFile.length ? (int)tmp_filesize : newFirstPartOfFile.length,
																this.m_fileEncoding));
									} catch (Exception e) {
										m_log.error("run() : " + m_filePath + " " + e.getMessage(), e);
									}
								}

								// ファイルの冒頭部分が異なれば別ファイルと判定
								if (!Arrays.equals(refFirstPartOfFile, newFirstPartOfFile)) {
									m_log.debug("run() : " + m_filePath + " log rotation detected");
									logrotateFlag = true;
									m_log.debug("run() : m_logrotate set true .2");
								}
							}
						}
					} catch (Exception e) {
						m_log.error("run() : " + m_filePath + " " + e.getMessage(), e);
					} finally {
						// ファイルのオフセットを元に戻す
						if (bufSeek != 0) {
							m_fr.seek(bufSeek);
						}

						// オープンしたファイルをクローズ
						if(newFile != null){
							try {
								newFile.close();
							} catch (Exception e) {
								m_log.error("run() : " + m_filePath + " " + e.getMessage(), e);
							}
						}
					}
					// 最終詳細チェック（ファイルデータ比較）実行時刻を設定
					m_lastDataCheck = System.currentTimeMillis();
				}

				// ログローテートされたと判定された場合
				if(logrotateFlag){
					// 再度ファイルサイズの増加を確認する。
					// ローテートされたと判定されたが、実は、ローテートされておらず、
					//  （１）の時点では最終ログ読み込み時からログが出力されていないためサイズ（filesize）は同一であったが、
					//  （２）の判定直前にログが出力された場合は、ローテートされたと誤検知するため、
					// 再度サイズ比較を行う。
					if (tmp_filesize == m_fr.length()) {
						m_log.info(m_filePath + " : file changed");
						closeFile(m_fr);

						// ファイルオープン
						m_fr = openFile(file, false);
						if (m_fr == null) {
							return;
						}

						tmp_filesize = m_fr.length();
						m_filesize = 0;
						// carryOver = "";
						m_carryOverBuf = new byte[0];
					}

				}
				n_unchanged_stats = 0;
			}

			n_unchanged_stats = 0;

			if (m_filesize < tmp_filesize) {
				// デバッグログ
				m_log.debug("run() : " + m_filePath +
						" filesize " + m_filesize + " tmp_filesize " + tmp_filesize);

				while (true) {
					readSuccessFlg = false;
					byte[] cbuf = new byte[1024]; // ファイルからの読み出し分を保持するバッファ
					int read = m_fr.read(cbuf);
					readSuccessFlg = true;
					if (read == -1) {
						break;
					}

					// //
					// UTF-8を含むログで文字化けが発生するため修正
					//
					// 最後の改行コードを検索し、最後の改行コード以降は次回に繰越て処理する。
					// carryOverStartには改行コードの次のコードのインデックスが格納される。
					int carryOverStart = read;
					boolean returnCode = false;
					for (int i = read - 1; i >= 0; i--) {
						if (cbuf[i] == m_lineSeparator) {
							carryOverStart = i + 1;
							returnCode = true;
							break;
						}
					}

					// デバッグログ
					m_log.debug("run() : " + m_filePath + " read " + read +
							"    carryOverStart " + carryOverStart);

					// 今回出力処理する分のバッファを作成
					// 前回の繰越分と今回ファイルから読み出したデータのうち
					// 最後の改行までのデータをマージする。
					byte[] appendedBuf = new byte[m_carryOverBuf.length + carryOverStart];
					ByteBuffer.wrap(m_carryOverBuf).get(appendedBuf, 0,
							m_carryOverBuf.length);
					ByteBuffer.wrap(cbuf).get(appendedBuf,
							m_carryOverBuf.length, carryOverStart);

					// デバッグログ
					m_log.debug("run() : " + m_filePath + " appendedBuf size " + appendedBuf.length);

					// 改行コードが含まれない場合
					if (!returnCode) {
						// 今回ファイルから読み込んだものを含めて全て次回へ繰り越す。
						// 出力処理は実施しない。
						m_log.debug("run() : " + m_filePath
								+ " return code is not exist");
						m_carryOverBuf = new byte[appendedBuf.length];
						ByteBuffer.wrap(appendedBuf).get(m_carryOverBuf);
						
						// 繰越データが非常に長い場合はバッファをカットする
						if(m_carryOverBuf != null  
								&& m_carryOverBuf.length > MonitorStringUtil._messageLimitLength){
							m_log.info("run() : " + m_filePath + " carryOverBuf size = " + m_carryOverBuf.length + ". carryOverBuf is too long. it cut down .(see monitor.logfile.message.length)");
							
							byte[] tmpBuf = new byte[MonitorStringUtil._messageLimitLength];
							ByteBuffer.wrap(m_carryOverBuf,0,MonitorStringUtil._messageLimitLength).get(tmpBuf);
							m_carryOverBuf = tmpBuf;
						}
						continue;
					}

					// 繰越データ用バッファを作成
					m_carryOverBuf = new byte[(read - carryOverStart)];
					try {
						// 最後が改行コード以降にデータがある場合は、次回の処理にまわす
						if (read > carryOverStart) {
							// デバッグログ
							if (m_log.isDebugEnabled()) {
								m_log.debug("run() : " + m_filePath
										+ " carryOverBuf size "
										+ m_carryOverBuf.length);
							}
							ByteBuffer.wrap(cbuf, carryOverStart,
									m_carryOverBuf.length).get(m_carryOverBuf);
						}
					} catch (Exception e) {
						m_log.error("run() : " + e.getMessage(), e);
					}
					
					try {
						// 加分読み込み
						String tmpString = new String(appendedBuf, 0,
								appendedBuf.length, this.m_fileEncoding);
						String[] result = tmpString.split(m_lineSeparatorString);
						// デバッグログ
						if (m_log.isDebugEnabled()) {
							m_log.debug("run() : " + m_filePath + " " + tmpString);
							m_log.debug("run() : " + m_filePath + " size "
									+ tmpString.length());
							m_log.debug("run() : " + m_filePath + " result size "
									+ result.length);
						}

						// 読み込み文字列のサイズが0でない場合は処理する
						if (tmpString.length() != 0) {
							for (String res : result) {
								// 旧バージョンとの互換性のため、syslogでも飛ばせるようにする。
								if (m_syslog.isValid()) {
									// v3.2 mode
									String logPrefix = LogfileMonitorConfig.program + "(" + m_filePath + "):";
									m_syslog.log(logPrefix + res);
								} else {
									// v4.0 mode
									MonitorStringUtil.patternMatch(MonitorStringUtil.formatLine(res), m_monitorInfo, m_filePath);
								}
							}
						}

					} catch (UnsupportedEncodingException e) {
						m_log.error("run() : " + m_filePath + " "
								+ e.getMessage());
					}
				}

				m_filesize = tmp_filesize;
				if(m_log.isDebugEnabled()){
					m_log.debug("run() : " + m_filePath + " filesize = " + m_filesize + ", FilePointer = " + m_fr.getFilePointer());
				}

			} else if (m_filesize > tmp_filesize) {
				// 切詰
				m_log.info(m_filePath + " : file size becomes small");
				m_fr.seek(0);
				m_filesize = 0;
				m_carryOverBuf = new byte[0];
			}

		} catch (IOException e) {
			if (readSuccessFlg) {
				m_log.error("run() : " + e.getMessage());
				Object[] args = { m_filePath };
				// message.log.agent.1=ログファイル「{0}」
				// message.log.agent.4=ファイルの読み込みに失敗しました
				sendMessage(PriorityConstant.TYPE_WARNING,
						Messages.getString("log.agent"),
						MESSAGE_ID_WARNING,
						Messages.getString("message.log.agent.4"),
						Messages.getString("message.log.agent.1", args) + "\n" + e.getMessage());

				// エラーが発生したのでファイルクローズ
				closeFile(m_fr);
			} else {
				m_log.warn("run() : " + e.getMessage());
				// 増加分読み込みの部分でエラーが起きた場合は、ファイルポインタを進める
				try {
					m_fr.seek(m_filesize);
				} catch (IOException e1) {
					m_log.error("run() set file-pointer error : " + e1.getMessage());
				}
			}
		}
	}

	/**
	 * 監視管理情報へ通知
	 * 
	 * @param priority
	 *            重要度
	 * @param app
	 *            アプリケーション
	 * @param msgId
	 *            メッセージID
	 * @param msg
	 *            メッセージ
	 * @param msgOrg
	 *            オリジナルメッセージ
	 */
	private void sendMessage(int priority, String app, String msgId, String msg, String msgOrg) {
		LogfileMonitorManager.sendMessage(m_filePath, priority, app, msgId, msg, msgOrg, m_monitorInfo.getMonitorId());
	}

	/**
	 * 転送対象ログファイルクローズ
	 * 
	 */
	private void closeFile(RandomAccessFileWrapper fr) {
		if (fr != null) {
			try {
				fr.close();
			} catch (IOException e) {
				m_log.debug("run() : " + e.getMessage());
			}
		}
	}

	/**
	 * 転送対象ログファイルオープン
	 */
	private RandomAccessFileWrapper openFile(File name, boolean init) {
		m_log.info("openFile : filename=" + name + ", init=" + init);

		RandomAccessFileWrapper fr = null;
		// ファイルオープン
		try {
			fr = new RandomAccessFileWrapper(name, "r");
			long filesize = fr.length();
			if (filesize > LogfileMonitorConfig.fileMaxSize) {
				// ファイルサイズが大きい場合、監視管理へ通知
				// message.log.agent.1=ログファイル「{0}」
				// message.log.agent.3=ファイルサイズが上限を超えました
				// message.log.agent.5=ファイルサイズ「{0} byte」
				Object[] args1 = { m_filePath };
				Object[] args2 = { filesize };
				sendMessage(PriorityConstant.TYPE_INFO,
						Messages.getString("agent"),
						MESSAGE_ID_INFO,
						Messages.getString("message.log.agent.3"),
						Messages.getString("message.log.agent.1", args1) + ", "
								+ Messages.getString("message.log.agent.5",args2));
			}

			// ファイルポインタの設定
			// 初回openはinit=trueで途中から読む。
			// ローテーションの再openはinit=falseで最初から読む。
			if (init) {
				fr.seek(filesize);
			}

			// 一度以上ファイルオープンに成功したら、フラグを無効化する
			m_firstOpenStatus = false;
		} catch (FileNotFoundException e) {
			m_log.info("openFile : " + e.getMessage());
			if (m_initFlag) {
				// 最初にファイルをチェックする場合、監視管理へ通知
				// message.log.agent.1=ログファイル「{0}」
				// message.log.agent.2=ログファイルがありませんでした
				Object[] args = { m_filePath };
				sendMessage(PriorityConstant.TYPE_INFO,
						Messages.getString("agent"),
						MESSAGE_ID_INFO,
						Messages.getString("message.log.agent.2"),
						Messages.getString("message.log.agent.1", args));
			}

			/*
			// 一度ファイルが見えなくなったら、次回オープン時は先頭から(仕様変更となるため実施しない)
			m_filesize = 0;
			m_firstHeadOpenStatus = true;
			 */
		} catch (SecurityException e) {
			m_log.info("openFile : " + e.getMessage());
			if (m_initFlag) {
				// 監視管理へ通知
				// message.log.agent.1=ログファイル「{0}」
				// message.log.agent.4=ファイルの読み込みに失敗しました
				Object[] args = { m_filePath };
				sendMessage(PriorityConstant.TYPE_WARNING,
						Messages.getString("agent"),
						MESSAGE_ID_WARNING,
						Messages.getString("message.log.agent.4"),
						Messages.getString("message.log.agent.1", args) + "\n" + e.getMessage());
			}
		} catch (IOException e) {
			m_log.info("openFile : " + e.getMessage());
			if (m_initFlag) {
				// 監視管理へ通知
				// message.log.agent.1=ログファイル「{0}」
				// message.log.agent.4=ファイルの読み込みに失敗しました
				Object[] args = { m_filePath };
				sendMessage(PriorityConstant.TYPE_INFO,
						Messages.getString("agent"),
						MESSAGE_ID_INFO,
						Messages.getString("message.log.agent.4"),
						Messages.getString("message.log.agent.1", args));
			}
			closeFile(fr);
		}
		m_initFlag = false;
		return fr;
	}
}