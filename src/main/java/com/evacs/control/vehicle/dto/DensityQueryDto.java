package com.evacs.control.vehicle.dto;

import com.evacs.control.vehicle.domain.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 차량 밀도 조회 조건.
 *
 * @param resolution H3 해상도. 생략하면 설정의 기본값을 쓴다. 숫자가 클수록 셀이 작다.
 */
public record DensityQueryDto(
		@DecimalMin("-90") @DecimalMax("90") Double minLat,
		@DecimalMin("-90") @DecimalMax("90") Double maxLat,
		@DecimalMin("-180") @DecimalMax("180") Double minLng,
		@DecimalMin("-180") @DecimalMax("180") Double maxLng,
		@Min(0) @Max(15) Integer resolution,
		String vehicleType) {

	public VehicleType type() {
		return VehicleType.from(vehicleType);
	}

	public boolean hasTypeFilter() {
		return vehicleType != null && !vehicleType.isBlank();
	}

	/** 밀도 집계는 차량 목록 조회와 같은 필터를 쓴다(신선한 차량만). */
	public VehicleQueryDto toVehicleQuery() {
		return new VehicleQueryDto(minLat, maxLat, minLng, maxLng, vehicleType, false);
	}
}
