package com.evacs.control.vehicle.dto;

import com.evacs.control.vehicle.domain.VehicleState;
import java.time.Instant;

/** 차량 1대의 대시보드 표시용 응답. */
public record VehicleResponse(
		String vehicleId,
		String vehicleType,
		double latitude,
		double longitude,
		double speedMps,
		double headingDegrees,
		Integer laneIndex,
		Integer laneCount,
		Instant measuredAt,
		Instant occurredAt,
		boolean stale) {

	public static VehicleResponse from(VehicleState state, boolean stale) {
		return new VehicleResponse(
				state.vehicleId(),
				state.vehicleType(),
				state.latitude(),
				state.longitude(),
				state.speedMps(),
				state.headingDegrees(),
				state.laneIndex(),
				state.laneCount(),
				state.measuredAt(),
				state.occurredAt(),
				stale);
	}
}
