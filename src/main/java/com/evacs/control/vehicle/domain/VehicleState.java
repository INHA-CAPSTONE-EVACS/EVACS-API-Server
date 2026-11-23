package com.evacs.control.vehicle.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Location Worker가 유지하는 {@code vehicle:{vehicleId}:state} Hash 1건.
 *
 * <p>ws-server가 쓰는 원본 위치 이벤트가 아니라, 워커가 중복·역행을 걸러 내고 H3 셀까지 붙여 둔
 * 정제 상태다(Location-Worker design.md §2, apply-location.lua). 그래서 lane 정보처럼 원본
 * 위치 이벤트에만 있던 필드는 여기에 없다.
 *
 * @param updatedAt    워커가 찍은 수신 시각({@code lastUpdatedAt}). 신선도는 기기 시계인
 *                     {@code measuredAt}이 아니라 이 값으로 판단한다. {@code h3:vehicles:{cell}}
 *                     ZSET의 score와 같은 시각이다.
 * @param status       워커의 정리 주기가 찍는 {@code ACTIVE}/{@code INACTIVE}.
 * @param stateVersion 워커의 단조 증가 버전. 같은 차량 응답의 선후 비교에 쓸 수 있다.
 */
public record VehicleState(
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
		String status) {

	private static final String STATUS_ACTIVE = "ACTIVE";

	/**
	 * HGETALL 결과를 상태로 옮긴다.
	 *
	 * <p>Hash 값은 전부 문자열이다. 워커가 필드를 추가해도 깨지지 않게 모르는 필드는 무시하고,
	 * 식별자나 좌표를 읽을 수 없으면 {@code null}을 돌려 호출자가 그 항목만 건너뛰게 한다.
	 */
	public static VehicleState fromHash(Map<String, String> hash) {
		String vehicleId = hash.get("vehicleId");
		if (vehicleId == null || vehicleId.isBlank()) {
			return null;
		}
		Double latitude = parseDouble(hash.get("latitude"));
		Double longitude = parseDouble(hash.get("longitude"));
		if (latitude == null || longitude == null) {
			return null;
		}
		return new VehicleState(
				vehicleId,
				hash.get("vehicleType"),
				latitude,
				longitude,
				orZero(parseDouble(hash.get("speedMps"))),
				orZero(parseDouble(hash.get("headingDegrees"))),
				hash.get("h3CellId"),
				orZero(parseLong(hash.get("lastSequence"))),
				// 측정 시각은 epoch milli, 수신 시각은 ISO 8601이다(워커 계약).
				parseEpochMilli(hash.get("lastMeasuredAt")),
				parseInstant(hash.get("lastUpdatedAt")),
				orZero(parseLong(hash.get("stateVersion"))),
				hash.get("status"));
	}

	public VehicleType type() {
		return VehicleType.from(vehicleType);
	}

	public boolean isEmergency() {
		return type() == VehicleType.EMERGENCY;
	}

	/**
	 * 활성 차량이 아닌지 판단한다.
	 *
	 * <p>워커의 정리 주기가 비활성 차량을 {@code INACTIVE}로 바꾸고 셀 ZSET에서 빼지만, 주기
	 * 사이에는 아직 ACTIVE로 남아 있을 수 있다. 그래서 status만 보지 않고 수신 시각도 함께 본다.
	 */
	public boolean isStale(Instant now, Duration activeTtl) {
		if (status != null && !STATUS_ACTIVE.equalsIgnoreCase(status)) {
			return true;
		}
		return updatedAt == null || updatedAt.isBefore(now.minus(activeTtl));
	}

	public boolean within(Double minLat, Double maxLat, Double minLng, Double maxLng) {
		if (minLat != null && latitude < minLat) {
			return false;
		}
		if (maxLat != null && latitude > maxLat) {
			return false;
		}
		if (minLng != null && longitude < minLng) {
			return false;
		}
		return maxLng == null || longitude <= maxLng;
	}

	private static Double parseDouble(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Double.valueOf(raw.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Long parseLong(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(raw.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Instant parseEpochMilli(String raw) {
		Long millis = parseLong(raw);
		return millis == null ? null : Instant.ofEpochMilli(millis);
	}

	private static Instant parseInstant(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(raw.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static double orZero(Double value) {
		return value == null ? 0d : value;
	}

	private static long orZero(Long value) {
		return value == null ? 0L : value;
	}
}
