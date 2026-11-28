package com.evacs.control.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.emergency.dto.AlertDeliveryLogResponse;
import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.dto.EmergencyResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Alert Worker가 남긴 알림 전송 이력 조회를 실제 MySQL에 대고 검증한다.
 *
 * <p>워커 본체가 이 레포에 없어 {@code alert_delivery_log} 행을 직접 INSERT해 흉내 낸다
 * (워커 소유 테이블이라 JPA 매핑이 없고 조회도 네이티브 SQL이다).
 *
 * <p>사전 조건: {@code docker compose -f docker/compose.yml up -d --wait}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AlertDeliveryLogQueryTest {

	@Autowired
	private TestRestTemplate http;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		for (String table : List.of(
				"alert_delivery_log",
				"emergency_route_outbox",
				"dispatch_route",
				"outbox_event",
				"event_log",
				"emergency_route",
				"emergency")) {
			jdbc.execute("TRUNCATE TABLE " + table);
		}
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
	}

	@Test
	@DisplayName("최근 발송이 먼저 오고 상태·시각이 그대로 나온다")
	void returnsLogsNewestFirst() {
		String id = createEmergency("AMB_201");
		insertLog(id, "alert-1", 1, "EVACS_CAR_000001", "SENT", 1, 42);
		insertLog(id, "alert-2", 1, "EVACS_CAR_000002", "ACKNOWLEDGED", 2, null);

		AlertDeliveryLogResponse logs = http.getForObject(
				"/control/emergencies/{id}/alert-logs", AlertDeliveryLogResponse.class, id);

		assertThat(logs.dispatchId()).isEqualTo(id);
		assertThat(logs.count()).isEqualTo(2);
		assertThat(logs.logs()).extracting("alertId").containsExactly("alert-2", "alert-1");

		AlertDeliveryLogResponse.Entry newest = logs.logs().get(0);
		assertThat(newest.targetVehicleId()).isEqualTo("EVACS_CAR_000002");
		assertThat(newest.alertStatus()).isEqualTo("ACKNOWLEDGED");
		assertThat(newest.selectionReason()).isEqualTo("IN_CONE");
		assertThat(newest.firstSentAt()).isNotNull();
		assertThat(newest.acknowledgedAt()).isNotNull();
		// NULL인 ETA가 0으로 뭉개지지 않아야 한다.
		assertThat(newest.estimatedArrivalSeconds()).isNull();
		assertThat(logs.logs().get(1).estimatedArrivalSeconds()).isEqualTo(42);
	}

	@Test
	@DisplayName("알림이 아직 없으면 빈 목록이고, 다른 긴급상황 기록은 섞이지 않는다")
	void emptyWhenNoAlerts() {
		String id = createEmergency("AMB_202");
		String other = createEmergency("AMB_203");
		insertLog(other, "alert-9", 1, "EVACS_CAR_000009", "SENT", 1, 10);

		AlertDeliveryLogResponse logs = http.getForObject(
				"/control/emergencies/{id}/alert-logs", AlertDeliveryLogResponse.class, id);

		assertThat(logs.count()).isZero();
		assertThat(logs.logs()).isEmpty();
	}

	@Test
	@DisplayName("없는 긴급상황은 404, ID 형식이 틀리면 400")
	void unknownEmergencyIs404() {
		ResponseEntity<String> notFound = http.getForEntity(
				"/control/emergencies/00000000-0000-0000-0000-000000000000/alert-logs", String.class);
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<String> badId =
				http.getForEntity("/control/emergencies/not-a-uuid/alert-logs", String.class);
		assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ---------- helpers ----------

	private String createEmergency(String vehicleId) {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				vehicleId, "FIRE", 37.45, 126.65, null, null, null, null, null, null);
		return http.postForEntity("/control/emergencies", dto, EmergencyResponse.class)
				.getBody()
				.id();
	}

	/** Alert Worker의 INSERT를 흉내 낸다. 발송 시각은 순서가 뒤집히지 않게 sentOffset초만큼 밀어 둔다. */
	private void insertLog(
			String dispatchId,
			String alertId,
			int version,
			String vehicleId,
			String status,
			int sentOffset,
			Integer etaSeconds) {
		jdbc.update(
				"""
				INSERT INTO alert_delivery_log
				  (alert_log_id, alert_id, alert_version, dispatch_id, target_vehicle_id,
				   selection_reason, estimated_arrival_seconds, alert_status,
				   first_sent_at, acknowledged_at, cleared_at, expires_at, clear_reason)
				VALUES (?, ?, ?, ?, ?, 'IN_CONE', ?, ?,
				        DATE_ADD('2026-11-20 00:00:00.000', INTERVAL ? SECOND),
				        CASE WHEN ? = 'ACKNOWLEDGED' THEN '2026-11-20 00:01:00.000' END,
				        NULL, NULL, NULL)
				""",
				UUID.randomUUID().toString(),
				alertId,
				version,
				dispatchId,
				vehicleId,
				etaSeconds,
				status,
				sentOffset,
				status);
	}
}
