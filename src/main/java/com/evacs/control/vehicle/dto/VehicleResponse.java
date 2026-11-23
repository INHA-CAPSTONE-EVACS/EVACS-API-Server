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
		String h3CellId,
		long lastSequence,
		Instant measuredAt,
		Instant updatedAt,
		long stateVersion,
		String status,
		boolean stale) {

	public static VehicleResponse from(VehicleState state, boolean stale) {
		return new VehicleResponse(
				state.vehicleId(),
				state.vehicleType(),
				state.latitude(),
				state.longitude(),
				state.speedMps(),
				state.headingDegrees(),
				state.h3CellId(),
				state.lastSequence(),
				state.measuredAt(),
				state.updatedAt(),
				state.stateVersion(),
				state.status(),
				stale);
	}
}
