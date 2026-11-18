package com.evacs.control.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis 스냅샷 해시(`evacs:vehicle:location`)에 들어 있는 차량 1대의 최신 상태.
 *
 * <p>evacs-ws-server의 {@code LocationUpdatedEvent}를 그대로 저장한 JSON이라 필드가 1:1로 대응한다.
 * 서버가 필드를 추가해도 깨지지 않도록 알 수 없는 속성은 무시한다.
 *
 * @param occurredAt 서버가 수신 시각으로 찍은 값. 신선도 판단은 기기 시계인 measuredAt이 아니라
 *                   이 값으로 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VehicleState(
		String eventId,
		String vehicleId,
		String vehicleType,
		double latitude,
		double longitude,
		double speedMps,
		double headingDegrees,
		Integer laneIndex,
		Integer laneCount,
		Instant measuredAt,
		long sequence,
		Instant occurredAt) {

	public VehicleType type() {
		return VehicleType.from(vehicleType);
	}

	public boolean isEmergency() {
		return type() == VehicleType.EMERGENCY;
	}

	/**
	 * 스냅샷 해시 필드는 자동으로 지워지지 않는다. 떠난 차량의 마지막 위치가 남으므로
	 * 소비 측이 occurredAt으로 신선도를 판단해야 한다.
	 */
	public boolean isStale(Instant now, Duration staleAfter) {
		return occurredAt == null || occurredAt.isBefore(now.minus(staleAfter));
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
}
