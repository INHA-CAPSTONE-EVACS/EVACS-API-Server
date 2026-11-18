package com.evacs.control.vehicle.domain;

/** 차량 유형. client-core의 {@code VehicleType}('NORMAL' | 'EMERGENCY')와 같은 값이다. */
public enum VehicleType {
	NORMAL,
	EMERGENCY;

	/** 알 수 없는 문자열은 NORMAL로 보지 않고 null을 돌려 호출부가 판단하게 한다. */
	public static VehicleType from(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return VehicleType.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
