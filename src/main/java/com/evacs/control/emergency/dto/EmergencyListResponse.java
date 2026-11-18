package com.evacs.control.emergency.dto;

import java.util.List;

/** 긴급상황 목록 응답. */
public record EmergencyListResponse(int count, List<EmergencyResponse> emergencies) {

	public static EmergencyListResponse of(List<EmergencyResponse> emergencies) {
		return new EmergencyListResponse(emergencies.size(), emergencies);
	}
}
