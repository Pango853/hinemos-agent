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

package com.clustercontrol.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Value Collector Manager Class
 * 
 * @since 4.0
 */
public class CollectorManager {

	private static Log log = LogFactory.getLog(CollectorManager.class);

	private static final Map<CollectorId, CollectorTask> tasks;

	static {
		tasks = new ConcurrentHashMap<CollectorId, CollectorTask>();
	}

	/**
	 * register new Collector Task
	 * @param newTask new Collector Task
	 */
	public static synchronized void registerCollectorTask(CollectorTask newTask) {
		if (tasks.containsKey(newTask.getCollectorId())) {
			log.info("already collector running. (" + newTask.getCollectorId() + ")");
			CollectorTask oldTask = tasks.get(newTask.getCollectorId());
			oldTask.update(newTask);
		} else {
			tasks.put(newTask.getCollectorId(), newTask);
			log.info("starting new collector. (" + newTask.getCollectorId() + ")");
			newTask.start();
		}
	}

	/**
	 * unregister Collector Task
	 * @param collectorId Collector Id
	 */
	public static synchronized void unregisterCollectorTask(CollectorId collectorId) {
		if (tasks.containsKey(collectorId)) {
			log.info("stopping a collector. (" + collectorId + ")");
			tasks.get(collectorId).shutdown();
			tasks.remove(collectorId);
		} else {
			log.warn("collector is not running. (" + collectorId + ")");
		}
	}

	/**
	 * unregister Collector Task
	 * @param type type of Collector Task
	 */
	public static synchronized void unregisterCollectorTask(int type){
		List<CollectorId> collectorIds = getAllCollectorIds();

		for (CollectorId id : collectorIds) {
			if (type == id.type) {
				unregisterCollectorTask(id);
			}
		}
	}

	/**
	 * get all Collector Id List
	 * @return id list
	 */
	public static synchronized List<CollectorId> getAllCollectorIds() {
		List<CollectorId> collectorIds = new ArrayList<CollectorId>();

		for (CollectorId id : tasks.keySet()) {
			collectorIds.add(id);
		}

		return collectorIds;
	}

}
