package com.evacs.control.vehicle.dto;

import com.evacs.control.vehicle.domain.VehicleCountSummary;
import java.time.Instant;

/** 전체 차량 수 및 유형별 통계. */
public record VehicleSummaryResponse(
		long total, long normal, long emergency, long unknown, long stale, Instant queriedAt) {

	public static VehicleSummaryResponse from(VehicleCountSummary summary) {
		return new VehicleSummaryResponse(
				summary.total(),
				summary.normal(),
				summary.emergency(),
				summary.unknown(),
				summary.stale(),
				Instant.now());
	}
}
