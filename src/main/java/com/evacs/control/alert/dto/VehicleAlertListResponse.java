package com.evacs.control.alert.dto;

import com.evacs.control.alert.domain.VehicleAlert;
import java.time.Instant;
import java.util.List;

/**
 * 지금 알림이 떠 있는 차량 전체. 대시보드가 차량 목록과 vehicleId로 맞춰 지도에 표시한다.
 *
 * @param queriedAt      응답 생성 시각.
 * @param streamPolledAt 알림 스트림을 마지막으로 읽은 시각. 이 값이 멈춰 있으면 Redis 연결이나
 *                       Alert Worker 쪽을 봐야 한다(응답이 빈 것과 구분된다).
 */
public record VehicleAlertListResponse(
		int count, List<VehicleAlertResponse> alerts, Instant queriedAt, Instant streamPolledAt) {

	public static VehicleAlertListResponse of(List<VehicleAlert> alerts, Instant streamPolledAt) {
		List<VehicleAlertResponse> entries = alerts.stream().map(VehicleAlertResponse::from).toList();
		return new VehicleAlertListResponse(entries.size(), entries, Instant.now(), streamPolledAt);
	}
}
