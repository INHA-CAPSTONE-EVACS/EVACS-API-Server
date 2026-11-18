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
 * 긴급차량의 이동 경로 1개 지점.
 *
 * <p>{@code sequence}가 경로 순서이며 (emergencyId, sequence)가 유니크다.
 * 연관관계를 객체 참조가 아니라 emergencyId 컬럼으로 둔 것은 조회가 항상
 * "이 긴급상황의 경로 전체"이고, 지연로딩 연쇄로 N+1을 만들 이유가 없어서다.
 */
@Entity
@Table(name = "emergency_route")
public class EmergencyRoute {

	@Id
	// UUID를 MySQL binary(16)이 아니라 char(36)으로 둔다 — SQL 콘솔에서 바로 읽고
	// 네이티브 쿼리 결과도 문자열로 다룰 수 있다.
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false)
	private UUID id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "emergency_id", nullable = false)
	private UUID emergencyId;

	@Column(name = "sequence", nullable = false)
	private int sequence;

	@Column(name = "latitude", nullable = false)
	private double latitude;

	@Column(name = "longitude", nullable = false)
	private double longitude;

	@Column(name = "h3_index", length = 24)
	private String h3Index;

	@Column(name = "eta_seconds")
	private Integer etaSeconds;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected EmergencyRoute() {
		// JPA
	}

	public EmergencyRoute(
			UUID emergencyId,
			int sequence,
			double latitude,
			double longitude,
			String h3Index,
			Integer etaSeconds) {
		this.id = UUID.randomUUID();
		this.emergencyId = emergencyId;
		this.sequence = sequence;
		this.latitude = latitude;
		this.longitude = longitude;
		this.h3Index = h3Index;
		this.etaSeconds = etaSeconds;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getEmergencyId() {
		return emergencyId;
	}

	public int getSequence() {
		return sequence;
	}

	public double getLatitude() {
		return latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	public String getH3Index() {
		return h3Index;
	}

	public Integer getEtaSeconds() {
		return etaSeconds;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
