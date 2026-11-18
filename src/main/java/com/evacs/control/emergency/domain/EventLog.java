package com.evacs.control.emergency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 긴급상황에 따라 발생한 이벤트 전송 이력.
 *
 * <p>쓰는 쪽은 Emergency Worker이고 API 서버는 조회만 한다(설계서의 EventLogRepository는
 * findByEmergencyId() 하나뿐이다). payload는 이벤트 종류마다 형태가 달라 MySQL JSON 컬럼으로 둔다.
 */
@Entity
@Table(name = "event_log")
public class EventLog {

	@Id
	// UUID를 MySQL binary(16)이 아니라 char(36)으로 둔다 — SQL 콘솔에서 바로 읽고
	// 네이티브 쿼리 결과도 문자열로 다룰 수 있다.
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false)
	private UUID id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "emergency_id", nullable = false)
	private UUID emergencyId;

	@Column(name = "event_type", nullable = false, length = 64)
	private String eventType;

	@Column(name = "vehicle_id", length = 64)
	private String vehicleId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload")
	private String payload;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected EventLog() {
		// JPA
	}

	public EventLog(UUID emergencyId, String eventType, String vehicleId, String payload, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.emergencyId = emergencyId;
		this.eventType = eventType;
		this.vehicleId = vehicleId;
		this.payload = payload;
		this.occurredAt = occurredAt;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getEmergencyId() {
		return emergencyId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getVehicleId() {
		return vehicleId;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
