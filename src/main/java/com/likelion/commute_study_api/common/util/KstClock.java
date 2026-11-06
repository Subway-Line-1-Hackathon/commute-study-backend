package com.likelion.commute_study_api.common.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.common.config.AppProperties;

/**
 * 하루 단위 경계(정답률, AI 질문 횟수, 이어하기 만료)를 app.timezone(기본 Asia/Seoul) 자정 기준으로 맞춘다.
 */
@Component
public class KstClock {

	private final ZoneId zoneId;

	public KstClock(AppProperties appProperties) {
		this.zoneId = ZoneId.of(appProperties.getTimezone());
	}

	public ZoneId getZoneId() {
		return zoneId;
	}

	public ZonedDateTime now() {
		return ZonedDateTime.now(zoneId);
	}

	public LocalDate today() {
		return now().toLocalDate();
	}
}
