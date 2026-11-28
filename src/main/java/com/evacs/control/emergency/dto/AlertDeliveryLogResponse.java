package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.AlertDeliveryLog;
import java.time.Instant;
import java.util.List;

/**
 * Alert Worker가 남긴 양보 알림 전송 이력. 최근 발송이 먼저 온다.
 *
 * <p>{@code /alerted-vehicles}(Redis의 "수신 확인 차량 집합")와 달리 이쪽은 RDS에 남은 기록이라
 * 발송·확인·해제 시각과 상태 전이까지 보인다. 긴급상황이 끝난 뒤에도 남는다.
 */
public record AlertDeliveryLogResponse(String dispatchId, int count, List<Entry> logs) {

	public record Entry(
			String alertLogId,
			String alertId,
			int alertVersion,
			String targetVehicleId,
			String selectionReason,
			Integer estimatedArrivalSeconds,
			String alertStatus,
			Instant firstSentAt,
			Instant acknowledgedAt,
			Instant clearedAt,
			Instant expiresAt,
			String clearReason) {

		public static Entry from(AlertDeliveryLog log) {
			return new Entry(
					log.alertLogId(),
					log.alertId(),
					log.alertVersion(),
					log.targetVehicleId(),
					log.selectionReason(),
					log.estimatedArrivalSeconds(),
					log.alertStatus(),
					log.firstSentAt(),
					log.acknowledgedAt(),
					log.clearedAt(),
					log.expiresAt(),
					log.clearReason());
		}
	}

	public static AlertDeliveryLogResponse of(String dispatchId, List<AlertDeliveryLog> logs) {
		List<Entry> entries = logs.stream().map(Entry::from).toList();
		return new AlertDeliveryLogResponse(dispatchId, entries.size(), entries);
	}
}
