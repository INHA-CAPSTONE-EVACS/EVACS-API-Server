package com.evacs.control.emergency.dto;

import java.util.List;

/** 해당 긴급상황의 양보 알림 수신이 확인된 차량 목록. */
public record AlertedVehiclesResponse(String emergencyId, int count, List<String> vehicleIds) {

	public static AlertedVehiclesResponse of(String emergencyId, List<String> vehicleIds) {
		return new AlertedVehiclesResponse(emergencyId, vehicleIds.size(), vehicleIds);
	}
}
