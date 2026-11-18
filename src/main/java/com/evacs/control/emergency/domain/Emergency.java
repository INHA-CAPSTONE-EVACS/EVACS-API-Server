package com.evacs.control.emergency.domain;

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
 * 긴급상황 영속 엔티티.
 *
 * <p>상태 전이는 ACTIVE → ENDED 단방향이다. 되돌릴 일이 있으면 새 긴급상황을 만든다 —
 * 이미 발행된 종료 이벤트를 Worker가 취소할 방법이 없기 때문이다.
 */
@Entity
@Table(name = "emergency")
public class Emergency {

	@Id
	// UUID를 MySQL binary(16)이 아니라 char(36)으로 둔다 — SQL 콘솔에서 바로 읽고
	// 네이티브 쿼리 결과도 문자열로 다룰 수 있다.
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "vehicle_id", nullable = false, length = 64)
	private String vehicleId;

	@Enumerated(EnumType.STRING)
	// MySQL 네이티브 enum 대신 varchar로 둔다 — 값 추가가 DDL 변경이 되지 않게.
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "status", nullable = false, length = 16)
	private EmergencyStatus status;

	@Column(name = "emergency_type", nullable = false, length = 32)
	private String emergencyType;

	@Column(name = "origin_lat")
	private Double originLat;

	@Column(name = "origin_lng")
	private Double originLng;

	@Column(name = "destination_lat")
	private Double destinationLat;

	@Column(name = "destination_lng")
	private Double destinationLng;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "description")
	private String description;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Emergency() {
		// JPA
	}

	private Emergency(
			UUID id,
			String vehicleId,
			String emergencyType,
			Double originLat,
			Double originLng,
			Double destinationLat,
			Double destinationLng,
			String description,
			Instant now) {
		this.id = id;
		this.vehicleId = vehicleId;
		this.status = EmergencyStatus.ACTIVE;
		this.emergencyType = emergencyType;
		this.originLat = originLat;
		this.originLng = originLng;
		this.destinationLat = destinationLat;
		this.destinationLng = destinationLng;
		this.description = description;
		this.startedAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Emergency start(
			String vehicleId,
			String emergencyType,
			Double originLat,
			Double originLng,
			Double destinationLat,
			Double destinationLng,
			String description) {
		return new Emergency(
				UUID.randomUUID(),
				vehicleId,
				emergencyType,
				originLat,
				originLng,
				destinationLat,
				destinationLng,
				description,
				Instant.now());
	}

	/** 종료 전이. 이미 종료된 건은 호출부가 409로 막는다. */
	public void end(Instant endedAt) {
		this.status = EmergencyStatus.ENDED;
		this.endedAt = endedAt;
		this.updatedAt = endedAt;
	}

	public boolean isActive() {
		return status == EmergencyStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public String getVehicleId() {
		return vehicleId;
	}

	public EmergencyStatus getStatus() {
		return status;
	}

	public void setStatus(EmergencyStatus status) {
		this.status = status;
		this.updatedAt = Instant.now();
	}

	public String getEmergencyType() {
		return emergencyType;
	}

	public Double getOriginLat() {
		return originLat;
	}

	public Double getOriginLng() {
		return originLng;
	}

	public Double getDestinationLat() {
		return destinationLat;
	}

	public Double getDestinationLng() {
		return destinationLng;
	}

	public String getDescription() {
		return description;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
