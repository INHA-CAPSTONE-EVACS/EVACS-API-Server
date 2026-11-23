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
 * @param route        사전 계산된 경로 지점들. 비어 있어도 생성은 되며, 경로는 나중에 Worker가 채운다.
 * @param isSumo       true면 SUMO 모드다. 커밋 후 SUMO 시뮬레이터에 이 vehicleId로 긴급차량을
 *                     한 대 투입한다(실차가 아니라 시뮬레이션 차량을 만든다).
 * @param sumoDepotId  SUMO 모드에서 차량이 출발할 차고지 ID. 생략하면 설정 기본값을 쓴다.
 */
public record CreateEmergencyDto(
		@NotBlank @Size(max = 64) String vehicleId,
		@NotBlank @Size(max = 32) String emergencyType,
		@DecimalMin("-90") @DecimalMax("90") Double originLat,
		@DecimalMin("-180") @DecimalMax("180") Double originLng,
		@DecimalMin("-90") @DecimalMax("90") Double destinationLat,
		@DecimalMin("-180") @DecimalMax("180") Double destinationLng,
		String description,
		List<@NotNull RoutePoint> route,
		Boolean isSumo,
		@Size(max = 64) String sumoDepotId) {

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

	/** SUMO 모드 여부. 생략은 false(= 실차 모드)다. */
	public boolean sumoMode() {
		return Boolean.TRUE.equals(isSumo);
	}
}
