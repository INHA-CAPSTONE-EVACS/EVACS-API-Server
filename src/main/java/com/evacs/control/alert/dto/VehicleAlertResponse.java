package com.evacs.control.alert.dto;

import com.evacs.control.alert.domain.VehicleAlert;
import java.time.Instant;

/**
 * 차량 1대에 떠 있는 알림.
 *
 * <p>{@code alertType}이 "어떤 알람인가"고 {@code message}가 Worker가 만든 안내 문구다.
 * 둘 다 가공하지 않고 그대로 내려 준다.
 */
public record VehicleAlertResponse(
		String vehicleId,
		String alertType,
		String alertId,
		Integer alertVersion,
		String dispatchId,
		String approachDirection,
		String recommendedAction,
		String message,
		String reason,
		Instant occurredAt,
		Instant expiresAt,
		String eventId) {

	public static VehicleAlertResponse from(VehicleAlert alert) {
		return new VehicleAlertResponse(
				alert.vehicleId(),
				alert.alertType(),
				alert.alertId(),
				alert.alertVersion(),
				alert.dispatchId(),
				alert.approachDirection(),
				alert.recommendedAction(),
				alert.message(),
				alert.reason(),
				alert.occurredAt(),
				alert.expiresAt(),
				alert.eventId());
	}
}
