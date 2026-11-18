package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.EmergencyStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;

/**
 * 긴급상황 목록 조회 조건.
 *
 * @param status 'ACTIVE' | 'ENDED'. 생략하면 전체.
 * @param from   startedAt 하한(포함). 생략 가능.
 */
public record EmergencyQueryDto(
		String status,
		String vehicleId,
		Instant from,
		Instant to,
		@Min(1) @Max(500) Integer limit) {

	public EmergencyStatus statusOrNull() {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return EmergencyStatus.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("status는 ACTIVE 또는 ENDED여야 합니다: " + status);
		}
	}

	public int limitOrDefault() {
		return limit == null ? 100 : limit;
	}
}
