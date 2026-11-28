package com.evacs.control.alert.domain;

import java.time.Instant;

/**
 * 특정 차량에 도달한 알림 1건. Alert Worker가 {@code evacs:stream:yield-alert}로 발행한
 * Envelope 하나를 관제 화면이 쓸 모양으로 평평하게 펼친 것이다.
 *
 * <p>payload 필드를 골라 담되 원문 메시지({@code message})는 그대로 들고 간다 — 한국어 안내
 * 문구는 Worker가 만들고 관제 화면은 그걸 보여 주기만 한다.
 *
 * @param entryId Redis Stream entry id. 재조회 커서 전용이고 업무 식별자가 아니다.
 * @param eventId Envelope의 eventId. 같은 알림이 재전달되면 같은 값이 온다.
 */
public record VehicleAlert(
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
		String eventId,
		String entryId) {

	public static final String ISSUED = "YIELD_ALERT_ISSUED";
	public static final String CLEARED = "YIELD_ALERT_CLEARED";
	public static final String ROUTE_UPDATED = "EMERGENCY_ROUTE_UPDATED";

	/** 해제 이벤트는 "지금 떠 있는 알림"을 지우는 신호다(화면에 남겨 두지 않는다). */
	public boolean isCleared() {
		return CLEARED.equals(alertType);
	}

	/**
	 * 더 보여 줄 필요가 없는 알림인지 판정.
	 *
	 * <p>{@code expiresAt}이 있으면 그 시각이 기준이다(ws-server도 같은 규칙으로 지난 알림을
	 * 운전자에게 띄우지 않는다). 없으면 발생 시각 + ttl로 스스로 사라지게 한다 — 해제 이벤트가
	 * 유실돼도 화면에 알림이 영구히 남지 않아야 한다.
	 */
	public boolean isExpired(Instant now, long ttlMs) {
		if (expiresAt != null) {
			return now.isAfter(expiresAt);
		}
		return occurredAt == null || now.isAfter(occurredAt.plusMillis(ttlMs));
	}
}
