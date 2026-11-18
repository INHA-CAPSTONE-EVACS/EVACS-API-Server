package com.evacs.control.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outbox 이벤트.
 *
 * <p>긴급상황 상태 변경과 <b>같은 트랜잭션</b>에서 저장된다. DB 커밋이 곧 "이벤트가 반드시 발행된다"는
 * 보장이 되므로, 커밋은 됐는데 브로커 발행만 빠지는 상태가 생기지 않는다.
 * 반대로 중복 발행은 생길 수 있다(발행 성공 직후 markPublished 전에 죽는 경우) — 소비자인
 * Emergency Worker가 eventId로 멱등 처리해야 한다. at-least-once다.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

	@Id
	// UUID를 MySQL binary(16)이 아니라 char(36)으로 둔다 — SQL 콘솔에서 바로 읽고
	// 네이티브 쿼리 결과도 문자열로 다룰 수 있다.
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "aggregate_type", nullable = false, length = 32)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, length = 64)
	private String aggregateId;

	@Column(name = "event_type", nullable = false, length = 64)
	private String eventType;

	/** 브로커로 그대로 나가는 JSON 문자열. varchar(255)로는 모자라 TEXT로 둔다. */
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "payload", nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "status", nullable = false, length = 16)
	private OutboxStatus status;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	/** truncate()가 1000자로 자른다. */
	@Column(name = "last_error", length = 1000)
	private String lastError;

	@Column(name = "message_id", length = 64)
	private String messageId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboxEvent() {
		// JPA
	}

	private OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
		this.id = UUID.randomUUID();
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = OutboxStatus.PENDING;
		this.attempts = 0;
		this.createdAt = Instant.now();
	}

	public static OutboxEvent emergencyEvent(
			String emergencyId, EmergencyEventType eventType, String payload) {
		return new OutboxEvent("EMERGENCY", emergencyId, eventType.name(), payload);
	}

	public UUID getId() {
		return id;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public String getAggregateId() {
		return aggregateId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getPayload() {
		return payload;
	}

	public OutboxStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public String getMessageId() {
		return messageId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getClaimedAt() {
		return claimedAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}
}
