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

package com.clustercontrol.agent.custom;

import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.fault.HinemosException;
import com.clustercontrol.agent.util.AgentProperties;
import com.clustercontrol.agent.util.CalendarWSUtil;
import com.clustercontrol.agent.util.CollectorId;
import com.clustercontrol.agent.util.CollectorTask;
import com.clustercontrol.agent.util.CommandMonitoringWSUtil;
import com.clustercontrol.bean.PluginConstant;
import com.clustercontrol.bean.YesNoConstant;
import com.clustercontrol.util.CommandCreator;
import com.clustercontrol.util.CommandExecutor;
import com.clustercontrol.util.CommandExecutor.CommandResult;
import com.clustercontrol.util.StringBinder;
import com.clustercontrol.ws.monitor.CommandExecuteDTO;
import com.clustercontrol.ws.monitor.CommandResultDTO;
import com.clustercontrol.ws.monitor.CommandResultDTO.InvalidLines;
import com.clustercontrol.ws.monitor.CommandResultDTO.Results;
import com.clustercontrol.ws.monitor.CommandVariableDTO;

/**
 * Collector using command (register one Collector against one Command Monitoring)
 * 
 * @since 4.0
 */
public class CommandCollector implements CollectorTask, Runnable {

	private static Log log = LogFactory.getLog(CommandCollector.class);

	public static final int _collectorType = PluginConstant.TYPE_CUSTOM_MONITOR;

	// scheduler thread for configuration
	private ScheduledExecutorService _scheduler;

	// global configuration (thread pool and command properties)
	private static final ExecutorService _executorService;
	private static final Object _executorServiceLock = new Object();

	// collector's thread pool
	public static final int _workerThreads;
	public static final int _workerThreadsDefault = 8;

	public static final String _commandMode;
	public static final String _commandModeDefault = "auto";

	public static final int _bufferSize;
	public static final int _bufferSizeDefault = 512;

	public static final Charset _charset;
	public static final Charset _charsetDefault = Charset.forName("UTF-8");

	public static final String _returnCode;
	public static final String _returnCodeDefault = "\n";

	private CommandExecuteDTO config;

	static {
		// read count of threads
		String threadsStr = AgentProperties.getProperty("monitor.custom.thread");
		int threads = _workerThreadsDefault;
		try {
			threads = Integer.parseInt(threadsStr);
		} catch (NumberFormatException e) {
			log.info("monitor.custom.thread uses " + threads + ". (" + threadsStr + " is not collect)");
		}
		_workerThreads = threads;

		// read command mode
		String commandModeStr = AgentProperties.getProperty("monitor.custom.command.mode");
		if (! ("windows".equals(commandModeStr) || "unix".equals(commandModeStr) 
				|| "compatible".equals(commandModeStr) || "regacy".equals(commandModeStr)
				|| "auto".equals(commandModeStr))) {
			commandModeStr = _commandModeDefault;
		}
		_commandMode = commandModeStr;

		// read buffer size
		String bufferSizeStr = AgentProperties.getProperty("monitor.custom.buffer");
		int bufferSize = _bufferSizeDefault;
		try {
			bufferSize = Integer.parseInt(bufferSizeStr);
		} catch (NumberFormatException e) {
			log.info("monitor.custom.buffer uses " + _bufferSizeDefault + ". (" + bufferSizeStr + " is not collect)");
		}
		_bufferSize = bufferSize;

		// read charset
		String charsetStr = AgentProperties.getProperty("monitor.custom.charset");
		Charset charset = _charsetDefault;
		try {
			charset = Charset.forName(charsetStr);
		} catch (Exception e) {
			log.info("monitor.custom.charset uses " + _charsetDefault.toString() + ". (" + charsetStr + " is not collect)");
		}
		_charset = charset;

		// read return code
		String returnCodeStr = AgentProperties.getProperty("monitor.custom.lineseparator");
		if ("CRLF".equals(returnCodeStr)) {
			returnCodeStr = "\r\n";
		} else if ("LF".equals(returnCodeStr)) {
			returnCodeStr = "\n";
		} else if ("CR".equals(returnCodeStr)) {
			returnCodeStr = "\r";
		} else {
			returnCodeStr = _returnCodeDefault;
		}
		_returnCode = returnCodeStr;
		
		// initialize thread pool
		_executorService = Executors.newFixedThreadPool(
				_workerThreads,
				new ThreadFactory() {
					private volatile int _count = 0;
					@Override
					public Thread newThread(Runnable r) {
						return new Thread(r, "CommandCollectorWorker-" + _count++);
					}
				}
				);
	}

	public CommandCollector(CommandExecuteDTO config) {
		this.config = config;
	}

	@Override
	public synchronized void update(CollectorTask task) {
		if (! (task instanceof CommandCollector)) {
			log.warn("this is not instance of CommandCollector : " + task);
			return;
		}

		setConfig(((CommandCollector)task).getConfig());
	}

	public synchronized CommandExecuteDTO getConfig() {
		return config;
	}

	public synchronized void setConfig(CommandExecuteDTO newConfig) {
		CommandExecuteDTO currentConfig = config;
		this.config = newConfig;

		if (currentConfig.getInterval().intValue() != newConfig.getInterval().intValue()) {
			log.info("interval is changed. (old = " + currentConfig.getInterval() + ", new = " + newConfig.getInterval() + ")");
			shutdown();
			start();
		}
	}

	/**
	 * string to identify Collector
	 */
	@Override
	public CollectorId getCollectorId() {
		return new CollectorId(_collectorType, config.getMonitorId());
	}

	/**
	 * process called by scheduler thread
	 */
	@Override
	public void run() {
		if (config.getCalendar() != null && ! CalendarWSUtil.isRun(config.getCalendar())) {
			// do nothing because now is not allowed
			if (log.isDebugEnabled()) {
				log.debug("command execution is skipped because of calendar. [" + CommandMonitoringWSUtil.toStringCommandExecuteDTO(config) + ", " + config.getCalendar() + "]");
			}
			return;
		}

		for (CommandVariableDTO entry : config.getVariables()) {
			if (log.isDebugEnabled()) {
				log.debug("starting command worker. [" + CommandMonitoringWSUtil.toStringCommandExecuteDTO(config) + ", facilityId = " + entry.getFacilityId() + "]");
			}
			// start collector task
			synchronized(_executorServiceLock) {
				_executorService.submit(new CollectorCommandWorker(config, entry.getFacilityId()));
			}
		}

	}

	@Override
	public void start() {
		// determine startup delay (using monitorId for random seed)
		int delay = new Random(config.getMonitorId().hashCode()).nextInt(config.getInterval());

		// determine startup time
		//
		// example)
		//   delay : 15 [sec]
		//   interval : 300 [sec]
		//   now : 2000-1-1 00:00:10
		//   best startup : 2000-1-1 00:00:15
		long now = System.currentTimeMillis();
		long startup = config.getInterval() * (now / config.getInterval()) + delay;
		startup = startup > now ? startup : startup + config.getInterval();

		log.info("scheduling command collector. (" + this + ", startup = "
				+ String.format("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", new Date(startup))
				+ ", interval = "+ config.getInterval() + " [msec])");

		// initialize scheduler thread
		_scheduler = Executors.newSingleThreadScheduledExecutor(
				new ThreadFactory() {
					private volatile int _count = 0;
					@Override
					public Thread newThread(Runnable r) {
						return new Thread(r, "CommandCollectorScheduler-" + _count++);
					}
				}
				);

		// start scheduler
		// when agent startup. this is called twice. first execution is after interval to avoid double execution.
		_scheduler.scheduleWithFixedDelay(this, startup - now, config.getInterval(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void shutdown() {
		log.info("shutdown command collector. (" + this + ")");
		// shutdown scheduler
		_scheduler.shutdown();
	}

	@Override
	public String toString() {
		return "CommandCollector [config = " + CommandMonitoringWSUtil.toStringCommandExecuteDTO(config) + "]";
	}



	public class CollectorCommandWorker implements Callable<Void> {

		private final CommandExecuteDTO config;
		private final String facilityId;

		private final Pattern lineSplitter;
		private final Pattern stdoutPattern;
		private final static String _stdoutRegex = "^([^,]+),([0-9.]+)$";

		public CollectorCommandWorker(CommandExecuteDTO config, String facilityId) {
			this.config = config;
			this.facilityId = facilityId;

			lineSplitter = Pattern.compile(_returnCode);
			stdoutPattern = Pattern.compile(_stdoutRegex);
		}

		@Override
		public Void call() {
			// execute command
			String[] command = null;
			String commandBinded = null;

			// replace command parameter
			if (log.isDebugEnabled()) log.debug("replacing node parameters in command string. [comand = " + config.getCommand() + "]");
			Map<String, String> params = CommandMonitoringWSUtil.getVariable(config, facilityId);
			StringBinder strBinder = new StringBinder(params);
			commandBinded = strBinder.bindParam(config.getCommand());
			if (log.isDebugEnabled()) log.debug("replaced node parameters in command string. [command = " + commandBinded + "]");

			// generate command for platform
			CommandResult ret = null;
			Date executeDate = null;
			Date exitDate = null;
			try {
				executeDate = new Date();
				
				CommandCreator.PlatformType platform = CommandCreator.convertPlatform(_commandMode);
				command = CommandCreator.createCommand(config.getEffectiveUser(), commandBinded, platform, config.getSpecifyUser());

				// execute command
				CommandExecutor cmdExecutor = new CommandExecutor(command, _charset, config.getTimeout(), _bufferSize);
				cmdExecutor.execute();

				// receive result
				ret = cmdExecutor.getResult();
				if (log.isDebugEnabled()) {
					log.debug("exit code : " + (ret != null ? ret.exitCode : null));
					log.debug("stdout : " + (ret != null ? ret.stdout : null));
					log.debug("stderr : " + (ret != null ? ret.stderr : null));
				}				
			} catch (HinemosException e) {
				log.warn("command creation failure.", e);
			} finally {
				exitDate = new Date();
				
				// parse stdout
				Map<String, Double> values = new HashMap<String, Double>();
				Map<Integer, String> invalids = new HashMap<Integer, String>();
				parseStdout(ret != null ? ret.stdout : null, values, invalids);

				// send command result to manager
				String commandStr = "";
				for (String arg : command) {
					commandStr += commandStr == null ? arg : " " + arg;
				}
				String user = (config.getSpecifyUser() == YesNoConstant.TYPE_YES ? config.getEffectiveUser() : CommandCreator.getSysUser());
				sendResult(ret, values, invalids, user, commandStr, executeDate, exitDate);
			}
			return null;
		}

		/**
		 * parse command result(stdout)
		 * @param stdout stdout string
		 * @param ret pointer of hash which will store values.
		 * @param lines pointer of hash which will store invalid lines.
		 */
		private void parseStdout(String stdout, Map<String, Double> ret, Map<Integer, String> lines) {
			int lineNumber = 0;

			if (log.isDebugEnabled())
				log.debug("parsing stdout... : " + stdout);

			if (stdout == null) {
				return;
			}

			for (String line : lineSplitter.split(stdout)) {
				lineNumber++;
				if (log.isDebugEnabled())
					log.debug("parsing line... (line " + lineNumber + " : " + line + ")");

				Matcher matches = stdoutPattern.matcher(line);
				Double value = Double.NaN;

				if (! matches.find() || matches.groupCount() != 2) {
					log.warn("stdout format must be 2 column (String,Double). (line = " + line + ")");
					lines.put(lineNumber, line);
					continue;
				} else {
					try {
						value = Double.parseDouble(matches.group(2));
					} catch (NumberFormatException e) {
						log.info("not a number, collect as NaN (String,Double). (line = " + line + ")");
					}
				}

				if (! ret.containsKey(matches.group(1))) {
					if (log.isDebugEnabled())
						log.debug("parsed stdout : key = " + matches.group(1) + ", value = " + Double.toString(value));
					ret.put(matches.group(1), value);
				} else {
					// replace not a number if duplicated.
					log.warn("duplicate key in stdout. (key = " + matches.group(1) + ")");
					lines.put(lineNumber, line);
				}
			}
		}

		private void sendResult(CommandResult result, Map<String, Double> values, Map<Integer, String> invalids, String user, String commandLine, Date executeDate, Date exitDate) {
			CommandResultDTO dto = new CommandResultDTO();

			dto.setMonitorId(config.getMonitorId());
			dto.setFacilityId(facilityId);
			dto.setCommand(commandLine);
			dto.setUser(user);
			dto.setTimeout(result == null ? true : false);
			dto.setExitCode(result == null ? null : result.exitCode);
			dto.setStdout(result == null ? null : result.stdout);
			dto.setStderr(result == null ? null : result.stderr);

			// convert values for web service
			Results resultsDTO = new Results();
			if (values != null) {
				List<CommandResultDTO.Results.Entry> resultEntry = resultsDTO.getEntry();
				for (String key : values.keySet()) {
					CommandResultDTO.Results.Entry entry = new CommandResultDTO.Results.Entry();
					entry.setKey(key);
					entry.setValue(values.get(key));
					resultEntry.add(entry);
				}
			}
			dto.setResults(resultsDTO);

			// convert invalid lines for web service
			InvalidLines invalidLinesDTO = new InvalidLines();
			if (invalids != null) {
				List<CommandResultDTO.InvalidLines.Entry> invalidLinesEntry = invalidLinesDTO.getEntry();
				for (Integer key : invalids.keySet()) {
					CommandResultDTO.InvalidLines.Entry entry = new CommandResultDTO.InvalidLines.Entry();
					entry.setKey(key);
					entry.setValue(invalids.get(key));
					invalidLinesEntry.add(entry);
				}
			}
			dto.setInvalidLines(invalidLinesDTO);

			// store generation date (round-down execution time)
			dto.setCollectDate(executeDate.getTime() - executeDate.getTime() % config.getInterval());

			// convert execution time
			dto.setExecuteDate(executeDate.getTime());

			// convert exit time
			dto.setExitDate(exitDate.getTime());

			// send result to manager
			if (log.isDebugEnabled()) {
				log.debug("sending command result to manager. [" + CommandMonitoringWSUtil.toString(dto) + "]");
			}
			
			CommandResultForwarder.getInstance().add(dto);
		}

	}
	
}
