package com.evacs.control.vehicle.dto;

import com.evacs.control.vehicle.domain.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * 차량 목록 조회 조건. 대시보드가 보고 있는 지도 영역(bounding box)과 차량 유형으로 거른다.
 *
 * @param includeStale true면 오래된(떠난 것으로 보이는) 차량도 포함한다. 기본은 제외.
 */
public record VehicleQueryDto(
		@DecimalMin("-90") @DecimalMax("90") Double minLat,
		@DecimalMin("-90") @DecimalMax("90") Double maxLat,
		@DecimalMin("-180") @DecimalMax("180") Double minLng,
		@DecimalMin("-180") @DecimalMax("180") Double maxLng,
		String vehicleType,
		Boolean includeStale) {

	public VehicleType type() {
		return VehicleType.from(vehicleType);
	}

	public boolean hasTypeFilter() {
		return vehicleType != null && !vehicleType.isBlank();
	}

	public boolean includeStaleOrDefault() {
		return Boolean.TRUE.equals(includeStale);
	}
}
