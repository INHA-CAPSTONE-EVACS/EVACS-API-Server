package com.evacs.control.vehicle.dto;

import java.time.Instant;
import java.util.List;

/**
 * 차량 목록 응답.
 *
 * @param queriedAt 응답 생성 시각. REST Polling이라 클라이언트가 신선도를 판단할 기준이 필요하다.
 */
public record VehicleListResponse(int count, List<VehicleResponse> vehicles, Instant queriedAt) {

	public static VehicleListResponse of(List<VehicleResponse> vehicles) {
		return new VehicleListResponse(vehicles.size(), vehicles, Instant.now());
	}
}
