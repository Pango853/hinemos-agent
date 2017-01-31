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
import java.util.Date;

import com.clustercontrol.calendar.util.CalendarUtil;

/**
 * カレンダのUtilityクラス
 * 
 */
public class CalendarWSUtil {

	public static boolean isRun(com.clustercontrol.ws.calendar.CalendarInfo info) {
		return CalendarUtil.isRun(ws2common(info), new Date());
	}

	private static com.clustercontrol.calendar.bean.CalendarInfo ws2common(
			com.clustercontrol.ws.calendar.CalendarInfo wInfo) {
		if (wInfo == null) {
			return null;
		}

		com.clustercontrol.calendar.bean.CalendarInfo cInfo =
				new com.clustercontrol.calendar.bean.CalendarInfo();

		// cInfo.setDescription(wInfo.getDescription());
		cInfo.setId(wInfo.getId());
		// cInfo.setName(wInfo.getName());
		// cInfo.setRegDate(wInfo.getRegDate());
		// cInfo.setRegUser(wInfo.getRegUser());
		// cInfo.setUpdateDate(wInfo.getUpdateDate());
		// cInfo.setUpdateUser(wInfo.getUpdateUser());
		cInfo.setValidTimeFrom(wInfo.getValidTimeFrom());
		cInfo.setValidTimeTo(wInfo.getValidTimeTo());

		if (wInfo != null) {
			ArrayList<com.clustercontrol.calendar.bean.CalendarDetailInfo> list =
					new ArrayList<com.clustercontrol.calendar.bean.CalendarDetailInfo>();
			for (com.clustercontrol.ws.calendar.CalendarDetailInfo detail : wInfo.getCalendarDetailList()) {
				list.add(ws2commonD(detail));
			}
			cInfo.setCalendarDetailList(list);
		}
		return cInfo;
	}

	private static com.clustercontrol.calendar.bean.CalendarDetailInfo ws2commonD(
			com.clustercontrol.ws.calendar.CalendarDetailInfo wInfo) {
		if (wInfo == null) {
			return null;
		}

		com.clustercontrol.calendar.bean.CalendarDetailInfo cInfo =
				new com.clustercontrol.calendar.bean.CalendarDetailInfo();
		cInfo.setAfterday(wInfo.getAfterday());
		cInfo.setDate(wInfo.getDate());
		cInfo.setDayOfWeek(wInfo.getDayOfWeek());
		cInfo.setDayOfWeekInMonth(wInfo.getDayOfWeekInMonth());
		cInfo.setDayType(wInfo.getDayType());
		// cInfo.setDescription(wInfo.getDescription());
		// cInfo.setEtcDays(wInfo.getEtcDays());
		//cInfo.setEtcInfo(ws2commonE(wInfo.getEtcInfo()));
		cInfo.setCalPatternId(wInfo.getCalPatternId());
		cInfo.setCalPatternInfo(ws2commonE(wInfo.getCalPatternInfo()));
		cInfo.setMonth(wInfo.getMonth());
		cInfo.setOperateFlg(wInfo.isOperateFlg());
		cInfo.setTimeFrom(wInfo.getTimeFrom());
		cInfo.setTimeTo(wInfo.getTimeTo());
		cInfo.setYear(wInfo.getYear());

		return cInfo;
	}
	private static com.clustercontrol.calendar.bean.CalendarPatternInfo ws2commonE(
			com.clustercontrol.ws.calendar.CalendarPatternInfo wInfo) {

		if (wInfo == null) {
			return null;
		}
		com.clustercontrol.calendar.bean.CalendarPatternInfo cInfo =
				new com.clustercontrol.calendar.bean.CalendarPatternInfo();
		//TODO
		if (wInfo.getYmd() != null) {
			ArrayList<com.clustercontrol.calendar.bean.YMD> list =
					new ArrayList<com.clustercontrol.calendar.bean.YMD>();
			for (com.clustercontrol.ws.calendar.Ymd ymd : wInfo.getYmd()) {
				list.add(ws2commonY(ymd));
				//cInfo.getYmd().add(ws2commonY(ymd));
			}
			cInfo.setYmd(list);
		}

		return cInfo;
	}

	private static com.clustercontrol.calendar.bean.YMD ws2commonY(
			com.clustercontrol.ws.calendar.Ymd wInfo) {
		com.clustercontrol.calendar.bean.YMD cInfo =
				new com.clustercontrol.calendar.bean.YMD();
		cInfo.setDay(wInfo.getDay());
		cInfo.setMonth(wInfo.getMonth());
		cInfo.setYear(wInfo.getYear());
		return cInfo;
	}
}
