package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.EventLog;
import java.time.Instant;

/** 긴급상황 관련 이벤트 전송 이력 1건. */
public record EventLogResponse(
		String id, String eventType, String vehicleId, String payload, Instant occurredAt) {

	public static EventLogResponse from(EventLog log) {
		return new EventLogResponse(
				log.getId().toString(),
				log.getEventType(),
				log.getVehicleId(),
				log.getPayload(),
				log.getOccurredAt());
	}
}
