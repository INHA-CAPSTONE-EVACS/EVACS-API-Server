package com.evacs.control.emergency.domain;

import java.time.Instant;

/**
 * Alert Worker가 {@code alert_delivery_log}에 남긴 양보 알림 1건.
 *
 * <p>dispatch_route와 같은 이유로 JPA 엔티티가 아니다 — 이 테이블의 소유자는 Alert Worker이고
 * 관제 API는 읽기만 한다(V3__worker_tables.sql).
 *
 * <p>행 단위는 "발송 1건"이 아니라 "발송부터 해제까지 1건"이다(schema-diff.md). 해제 후 같은
 * {@code alertId}로 다시 발송되면 새 행이 생기고 {@code alertVersion}이 올라간다.
 */
public record AlertDeliveryLog(
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
}
