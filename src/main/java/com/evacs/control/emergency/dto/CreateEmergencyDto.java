package com.evacs.control.emergency.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 긴급상황 생성 요청.
 *
 * @param route 사전 계산된 경로 지점들. 비어 있어도 생성은 되며, 경로는 나중에 Worker가 채운다.
 */
public record CreateEmergencyDto(
		@NotBlank @Size(max = 64) String vehicleId,
		@NotBlank @Size(max = 32) String emergencyType,
		@DecimalMin("-90") @DecimalMax("90") Double originLat,
		@DecimalMin("-180") @DecimalMax("180") Double originLng,
		@DecimalMin("-90") @DecimalMax("90") Double destinationLat,
		@DecimalMin("-180") @DecimalMax("180") Double destinationLng,
		String description,
		List<@NotNull RoutePoint> route) {

	/** @param sequence 경로 순서. 생략하면 목록 순서를 쓴다. */
	public record RoutePoint(
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
			Integer sequence,
			Integer etaSeconds) {
	}

	public List<RoutePoint> routeOrEmpty() {
		return route == null ? List.of() : route;
	}
}
