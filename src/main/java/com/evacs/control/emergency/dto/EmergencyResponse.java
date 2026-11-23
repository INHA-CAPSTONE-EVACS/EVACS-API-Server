package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.Emergency;
import java.time.Instant;

/** 긴급상황 상세 응답. */
public record EmergencyResponse(
		String id,
		String vehicleId,
		String status,
		String emergencyType,
		Double originLat,
		Double originLng,
		Double destinationLat,
		Double destinationLng,
		String description,
		Instant startedAt,
		Instant endedAt,
		boolean isSumo) {

	public static EmergencyResponse from(Emergency e) {
		return new EmergencyResponse(
				e.getId().toString(),
				e.getVehicleId(),
				e.getStatus().name(),
				e.getEmergencyType(),
				e.getOriginLat(),
				e.getOriginLng(),
				e.getDestinationLat(),
				e.getDestinationLng(),
				e.getDescription(),
				e.getStartedAt(),
				e.getEndedAt(),
				e.isSumo());
	}
}
