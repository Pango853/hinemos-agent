/*

Copyright (C) 2014 NTT DATA Corporation

This program is free software; you can redistribute it and/or
Modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, version 2.

This program is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE.  See the GNU General Public License for more details.

 */

package com.clustercontrol.agent.winevent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.agent.AgentEndPointWrapper;
import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.ws.agent.MessageInfo;
import com.clustercontrol.ws.agent.WinEventResultDTO;
import com.clustercontrol.ws.monitor.MonitorInfo;
import com.clustercontrol.ws.monitor.MonitorStringValueInfo;

public class WinEventResultForwarder {
	
	private static Log log = LogFactory.getLog(WinEventResultForwarder.class);
	
	private static final WinEventResultForwarder _instance = new WinEventResultForwarder();
	
	private final ScheduledExecutorService _scheduler;
	
	public final int _queueMaxSize;
	
	public final int _transportMaxSize;
	public final int _transportMaxTries;
	public final int _transportIntervalSize;
	public final long _transportIntervalMSec;
	
	private AtomicInteger transportTries = new AtomicInteger(0);
	
	private List<Result> forwardList = new ArrayList<Result>();
	
	private WinEventResultForwarder() {
		{
			String key = "monitor.winevent.forwarding.queue.maxsize";
			int valueDefault = 5000;
			String str = AgentProperties.getProperty(key);
			int value = valueDefault;
			try {
				value = Integer.parseInt(str);
				if (value != -1 && value < 1) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				value = valueDefault;
			} finally {
				log.info(key + " uses value \"" + value + "\". (configuration = \"" + str + "\")");
			}
			_queueMaxSize = value;
		}
		
		{
			String key = "monitor.winevent.forwarding.transport.maxsize";
			int valueDefault = 100;
			String str = AgentProperties.getProperty(key);
			int value = valueDefault;
			try {
				value = Integer.parseInt(str);
				if (value != -1 && value < 1) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				value = valueDefault;
			} finally {
				log.info(key + " uses value \"" + value + "\". (configuration = \"" + str + "\")");
			}
			_transportMaxSize = value;
		}
		
		{
			String key = "monitor.winevent.forwarding.transport.maxtries";
			int valueDefault = 900;
			String str = AgentProperties.getProperty(key);
			int value = valueDefault;
			try {
				value = Integer.parseInt(str);
				if (value != -1 && value < 1) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				value = valueDefault;
			} finally {
				log.info(key + " uses value \"" + value + "\". (configuration = \"" + str + "\")");
			}
			_transportMaxTries = value;
		}
		
		{
			String key = "monitor.winevent.forwarding.transport.interval.size";
			int valueDefault = 15;
			String str = AgentProperties.getProperty(key);
			int value = valueDefault;
			try {
				value = Integer.parseInt(str);
				if (value != -1 && value < 1) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				value = valueDefault;
			} finally {
				log.info(key + " uses value \"" + value + "\". (configuration = \"" + str + "\")");
			}
			_transportIntervalSize = value;
		}
		
		{
			String key = "monitor.winevent.forwarding.transport.interval.msec";
			long valueDefault = 1000L;
			String str = AgentProperties.getProperty(key);
			long value = valueDefault;
			try {
				value = Long.parseLong(str);
				if (value != -1 && value < 1) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				value = valueDefault;
			} finally {
				log.info(key + " uses value \"" + value + "\". (configuration = \"" + str + "\")");
			}
			_transportIntervalMSec = value;
		}
		
		_scheduler = Executors.newSingleThreadScheduledExecutor(
				new ThreadFactory() {
					private volatile int _count = 0;
					@Override
					public Thread newThread(Runnable r) {
						Thread t = new Thread(r, WinEventResultForwarder.class.getSimpleName() + _count++);
						t.setDaemon(true);
						return t;
					}
				});
		
		if (_transportIntervalMSec != -1) {
			_scheduler.scheduleWithFixedDelay(new ScheduledTask(), 0, _transportIntervalMSec, TimeUnit.MILLISECONDS);
		}
	}
	
	public static WinEventResultForwarder getInstance() {
		return _instance;
	}
	
	public void add(String message, MessageInfo msgInfo, MonitorInfo monitorInfo, MonitorStringValueInfo monitorStrValueInfo) {
		try {
			ForwardListLock.writeLock();
			
			if (_queueMaxSize != -1 && forwardList.size() >= _queueMaxSize) {
				log.warn("rejected new winevent monitor's result. queue is full : " + message);
				return;
			}
			
			forwardList.add(new Result(message, msgInfo, monitorInfo, monitorStrValueInfo));
			
			if (forwardList.size() != 0 && forwardList.size() % _transportIntervalSize == 0) {
				if (_transportIntervalSize != -1 && forwardList.size() % _transportIntervalSize == 0) {
					_scheduler.submit(new ScheduledTask());
				}
			}
		} finally {
			ForwardListLock.writeUnlock();
		}
	}
	
	private void forward() {
		try {
			ForwardListLock.writeLock();
			
			while (forwardList.size() > 0) {
				// JAX-WSの一時ファイル肥大化(/tmp/jaxwsXXX)へのワークアラウンド実装(リクエストサイズに上限を設ける)
				int transportSize = _transportMaxSize != -1 && forwardList.size() > _transportMaxSize ? _transportMaxSize : forwardList.size();
				// 送信失敗直後は1メッセージずつ送信(SOAPのアーキテクチャ上、timeoutなどでメッセージの重複受信は回避できないが、その重複数を最小化する）
				transportSize = transportTries.get() == 0 ? transportSize : 1;
				
				List<Result> forwardListPart = Collections.unmodifiableList(forwardList.subList(0, transportSize));
				if (forwardListPart.size() > 0) {
					try {
						List<WinEventResultDTO> dtoList = new ArrayList<WinEventResultDTO>(forwardListPart.size());
						for (Result result : forwardListPart) {
							WinEventResultDTO dto = new WinEventResultDTO();
							dto.setMessage(result._message);
							dto.setMsgInfo(result._msgInfo);
							dto.setMonitorInfo(result._monitorInfo);
							dto.setMonitorStrValueInfo(result._monitorStrValueInfo);
							dtoList.add(dto);
						}
						AgentEndPointWrapper.forwardWinEventResult(dtoList);
					} catch (Throwable t) {
						String msg = String.format("[%d/%d] failed forwarding winevent monitor's result (%d of %d) : %s ...", 
								transportTries.get(), _transportMaxTries, forwardListPart.size(), forwardList.size(), 
								forwardListPart.get(0)._message);
						if (log.isDebugEnabled()) {
							log.warn(msg, t);
						} else {
							log.warn(msg);
						}
						if (transportTries.incrementAndGet() >= _transportMaxTries && _transportMaxTries != -1) {
							msg = String.format("[%d/%d] give up forwarding winevent monitor's result (%d of %d) : %s ...", 
									transportTries.get(), _transportMaxTries, forwardListPart.size(), forwardList.size(), 
									forwardListPart.get(0)._message);
							log.warn(msg, t);
						} else {
							// retry
							return;
						}
					}
				}
				
				forwardList.removeAll(forwardListPart);
				transportTries.set(0);
			}
		} catch (Exception e) {
			log.warn("failed forwarding result.", e);
		} finally {
			ForwardListLock.writeUnlock();
		}
	}
	
	private class Result {
		public final String _message;
		public final MessageInfo _msgInfo;
		public final MonitorInfo _monitorInfo;
		public final MonitorStringValueInfo _monitorStrValueInfo;
		
		public Result(String message, MessageInfo msgInfo, MonitorInfo monitorInfo, MonitorStringValueInfo monitorStrValueInfo) {
			this._message = message;
			this._msgInfo = msgInfo;
			this._monitorInfo = monitorInfo;
			this._monitorStrValueInfo = monitorStrValueInfo;
		}
	}
	
	private class ScheduledTask implements Runnable {
		
		@Override
		public void run() {
			_instance.forward();
		}
		
	}
	
	private static class ForwardListLock {
		
		private static final ReentrantReadWriteLock _lock = new ReentrantReadWriteLock();
		
		public static void readLock() {
			_lock.readLock().lock();
		}
		
		public static void readUnlock() {
			_lock.readLock().unlock();
		}
		
		public static void writeLock() {
			_lock.writeLock().lock();
		}
		
		public static void writeUnlock() {
			_lock.writeLock().unlock();
		}
	}
	
}
