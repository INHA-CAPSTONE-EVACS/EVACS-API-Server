package com.evacs.control.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.alert.dto.VehicleAlertListResponse;
import com.evacs.control.alert.dto.VehicleAlertResponse;
import com.evacs.control.alert.service.VehicleAlertService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Alert Worker가 {@code evacs:stream:yield-alert}에 발행한 알림이 차량별 현황으로 보이는지 검증한다.
 *
 * <p>워커 본체가 이 레포에 없어 스트림 엔트리를 직접 XADD해 흉내 낸다. 테스트 프로파일은
 * 스케줄러를 끄고({@code evacs.alert.enabled=false}) {@link VehicleAlertService#poll()}을
 * 직접 불러 조회 시점을 고정한다.
 *
 * <p>차량 ID를 테스트마다 새로 만드는 이유: 서비스 상태는 스프링 컨텍스트와 수명이 같아서
 * 앞선 테스트가 남긴 알림이 같은 맵에 남아 있다(TTL 전에는 사라지지 않는다).
 *
 * <p>사전 조건: {@code docker compose -f docker/compose.yml up -d --wait}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VehicleAlertStreamTest {

	private static final String STREAM_KEY = "test:evacs:stream:yield-alert";

	@Autowired
	private TestRestTemplate http;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private VehicleAlertService alertService;

	@Test
	@DisplayName("양보 알림이 발행되면 해당 차량에 알림 종류와 안내 문구가 그대로 보인다")
	void exposesIssuedAlert() {
		String vehicleId = vehicleId();
		issue(vehicleId, "후방에서 긴급차량이 접근하고 있습니다.", Instant.now().plusSeconds(20));
		alertService.poll();

		VehicleAlertResponse alert = findInList(vehicleId);
		assertThat(alert).isNotNull();
		assertThat(alert.alertType()).isEqualTo("YIELD_ALERT_ISSUED");
		assertThat(alert.message()).isEqualTo("후방에서 긴급차량이 접근하고 있습니다.");
		assertThat(alert.approachDirection()).isEqualTo("REAR");
		assertThat(alert.alertVersion()).isEqualTo(1);

		ResponseEntity<Map> status = http.getForEntity(
				"/control/vehicles/" + vehicleId + "/alert", Map.class);
		assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(status.getBody()).containsEntry("alerted", true);
	}

	@Test
	@DisplayName("해제 이벤트가 오면 그 차량의 알림이 사라진다")
	void clearsAlert() {
		String vehicleId = vehicleId();
		issue(vehicleId, "양보해 주세요.", Instant.now().plusSeconds(20));
		alertService.poll();
		assertThat(findInList(vehicleId)).isNotNull();

		add(envelope("YIELD_ALERT_CLEARED", payload(vehicleId, null, "PASSED")));
		alertService.poll();

		assertThat(findInList(vehicleId)).isNull();
		ResponseEntity<Map> status = http.getForEntity(
				"/control/vehicles/" + vehicleId + "/alert", Map.class);
		assertThat(status.getBody()).containsEntry("alerted", false);
	}

	@Test
	@DisplayName("만료 시각이 지난 알림은 표시하지 않는다")
	void skipsExpiredAlert() {
		String vehicleId = vehicleId();
		issue(vehicleId, "지난 알림", Instant.now().minusSeconds(5));
		alertService.poll();

		assertThat(findInList(vehicleId)).isNull();
	}

	@Test
	@DisplayName("경로 변경 알림도 어떤 알람이 왔는지로 같이 보인다")
	void exposesRouteUpdatedAlert() {
		String vehicleId = vehicleId();
		Map<String, Object> payload = payload(vehicleId, null, null);
		payload.put("message", "긴급차량 경로가 변경되었습니다.");
		add(envelope("EMERGENCY_ROUTE_UPDATED", payload));
		alertService.poll();

		VehicleAlertResponse alert = findInList(vehicleId);
		assertThat(alert).isNotNull();
		assertThat(alert.alertType()).isEqualTo("EMERGENCY_ROUTE_UPDATED");
	}

	private String vehicleId() {
		return "EVACS_CAR_" + UUID.randomUUID().toString().substring(0, 8);
	}

	private void issue(String vehicleId, String message, Instant expiresAt) {
		Map<String, Object> payload = payload(vehicleId, expiresAt, null);
		payload.put("message", message);
		payload.put("approachDirection", "REAR");
		payload.put("recommendedAction", "YIELD_WHEN_SAFE");
		add(envelope("YIELD_ALERT_ISSUED", payload));
	}

	private Map<String, Object> payload(String vehicleId, Instant expiresAt, String reason) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("alertId", "ALT-" + vehicleId);
		payload.put("alertVersion", 1);
		payload.put("dispatchId", "DSP-001");
		payload.put("vehicleId", vehicleId);
		payload.put("targetVehicleId", vehicleId);
		if (expiresAt != null) {
			payload.put("expiresAt", expiresAt.toString());
		}
		if (reason != null) {
			payload.put("reason", reason);
		}
		return payload;
	}

	/** Alert Worker가 쓰는 Envelope JSON. 엔트리 필드는 eventKey + event 쌍이다. */
	private String envelope(String eventType, Map<String, Object> payload) {
		StringBuilder fields = new StringBuilder();
		payload.forEach((key, value) -> {
			if (!fields.isEmpty()) {
				fields.append(',');
			}
			fields.append('"').append(key).append("\":");
			fields.append(value instanceof Number ? String.valueOf(value) : "\"" + value + "\"");
		});
		return "{\"eventId\":\"" + UUID.randomUUID()
				+ "\",\"eventType\":\"" + eventType
				+ "\",\"schemaVersion\":1,\"occurredAt\":\"" + Instant.now()
				+ "\",\"producer\":\"alert-worker\",\"payload\":{" + fields + "}}";
	}

	private void add(String event) {
		Map<String, String> entry = new LinkedHashMap<>();
		entry.put("eventKey", UUID.randomUUID().toString());
		entry.put("event", event);
		redis.opsForStream().add(StreamRecords.mapBacked(entry).withStreamKey(STREAM_KEY));
	}

	private VehicleAlertResponse findInList(String vehicleId) {
		ResponseEntity<VehicleAlertListResponse> response =
				http.getForEntity("/control/vehicles/alerts", VehicleAlertListResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		VehicleAlertListResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.count()).isEqualTo(body.alerts().size());
		return body.alerts().stream()
				.filter(alert -> alert.vehicleId().equals(vehicleId))
				.findFirst()
				.orElse(null);
	}
}
