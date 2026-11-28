package com.evacs.control.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.dto.DispatchRouteHistoryResponse;
import com.evacs.control.emergency.dto.DispatchRouteResponse;
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
 * Emergency Worker가 바꾼 경로 조회를 실제 MySQL에 대고 검증한다.
 *
 * <p>워커 본체는 이 레포에 없으므로 {@code dispatch_route} 행을 직접 INSERT해 흉내 낸다
 * (테이블은 워커 소유라 JPA 매핑이 없고, 조회도 네이티브 SQL이다).
 *
 * <p>사전 조건: {@code docker compose -f docker/compose.yml up -d --wait}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DispatchRouteQueryTest {

	@Autowired
	private TestRestTemplate http;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		// MySQL의 TRUNCATE는 다중 테이블·CASCADE를 지원하지 않고 FK가 걸린 테이블도 거부한다.
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		for (String table :
				List.of("emergency_route_outbox", "dispatch_route", "outbox_event", "event_log", "emergency_route", "emergency")) {
			jdbc.execute("TRUNCATE TABLE " + table);
		}
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
	}

	@Test
	@DisplayName("최신 route_version 1건을 좌표까지 구조화해 돌려준다")
	void returnsLatestVersion() {
		String id = createEmergency("AMB_101");
		insertRoute(id, 1, "[{\"latitude\":37.45,\"longitude\":126.65}]", "[\"edge-a\"]", 300, null);
		insertRoute(
				id,
				2,
				"[{\"latitude\":37.46,\"longitude\":126.66},{\"latitude\":37.47,\"longitude\":126.67}]",
				"[\"edge-b\",\"edge-c\"]",
				240,
				"CONGESTION");

		DispatchRouteResponse route = http.getForObject(
				"/control/emergencies/{id}/dispatch-route", DispatchRouteResponse.class, id);

		assertThat(route.available()).isTrue();
		assertThat(route.dispatchId()).isEqualTo(id);
		assertThat(route.routeVersion()).isEqualTo(2);
		assertThat(route.estimatedDuration()).isEqualTo(240);
		assertThat(route.changeReason()).isEqualTo("CONGESTION");
		assertThat(route.coordinatesParsed()).isTrue();
		assertThat(route.coordinates()).hasSize(2);
		assertThat(route.coordinates().get(0).latitude()).isEqualTo(37.46);
		assertThat(route.coordinates().get(1).longitude()).isEqualTo(126.67);
		assertThat(route.roadSegments()).containsExactly("edge-b", "edge-c");
		assertThat(route.createdAt()).isNotNull();
	}

	@Test
	@DisplayName("이력은 route_version 오름차순이고 좌표는 개수만 담는다")
	void historyIsOrderedByVersion() {
		String id = createEmergency("AMB_102");
		insertRoute(id, 2, "[{\"latitude\":37.46,\"longitude\":126.66}]", "[]", 240, "CONGESTION");
		insertRoute(
				id,
				1,
				"[{\"latitude\":37.45,\"longitude\":126.65},{\"latitude\":37.46,\"longitude\":126.66}]",
				"[]",
				300,
				null);

		DispatchRouteHistoryResponse history = http.getForObject(
				"/control/emergencies/{id}/dispatch-route/history", DispatchRouteHistoryResponse.class, id);

		assertThat(history.count()).isEqualTo(2);
		assertThat(history.versions()).extracting("routeVersion").containsExactly(1, 2);
		assertThat(history.versions().get(0).coordinateCount()).isEqualTo(2);
		assertThat(history.versions().get(1).coordinateCount()).isEqualTo(1);
		assertThat(history.versions().get(1).changeReason()).isEqualTo("CONGESTION");
	}

	@Test
	@DisplayName("워커가 아직 경로를 쌓지 않았으면 404가 아니라 available=false")
	void missingDispatchRouteIsNotAnError() {
		String id = createEmergency("AMB_103");

		DispatchRouteResponse route = http.getForObject(
				"/control/emergencies/{id}/dispatch-route", DispatchRouteResponse.class, id);
		assertThat(route.available()).isFalse();
		assertThat(route.coordinates()).isEmpty();
		assertThat(route.routeVersion()).isNull();

		DispatchRouteHistoryResponse history = http.getForObject(
				"/control/emergencies/{id}/dispatch-route/history", DispatchRouteHistoryResponse.class, id);
		assertThat(history.count()).isZero();
		assertThat(history.versions()).isEmpty();
	}

	@Test
	@DisplayName("좌표 JSON이 기대 형태가 아니면 원문이 아니라 빈 배열 + coordinatesParsed=false")
	void unexpectedCoordinateShapeIsFlagged() {
		String id = createEmergency("AMB_104");
		insertRoute(id, 1, "{\"points\":[[126.65,37.45]]}", "[{\"edgeId\":\"edge-a\"}]", 300, null);

		DispatchRouteResponse route = http.getForObject(
				"/control/emergencies/{id}/dispatch-route", DispatchRouteResponse.class, id);

		assertThat(route.available()).isTrue();
		assertThat(route.coordinatesParsed()).isFalse();
		assertThat(route.coordinates()).isEmpty();
		// 객체 구간은 식별 가능하도록 JSON 원문으로 평탄화된다.
		assertThat(route.roadSegments()).containsExactly("{\"edgeId\":\"edge-a\"}");
	}

	@Test
	@DisplayName("없는 긴급상황은 404, ID 형식이 틀리면 400")
	void unknownEmergencyIs404() {
		ResponseEntity<String> notFound = http.getForEntity(
				"/control/emergencies/00000000-0000-0000-0000-000000000000/dispatch-route", String.class);
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<String> badHistory = http.getForEntity(
				"/control/emergencies/not-a-uuid/dispatch-route/history", String.class);
		assertThat(badHistory.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ---------- helpers ----------

	private String createEmergency(String vehicleId) {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				vehicleId, "FIRE", 37.45, 126.65, null, null, null, null, null, null);
		return http.postForEntity("/control/emergencies", dto, EmergencyResponse.class)
				.getBody()
				.id();
	}

	/** Emergency Worker의 INSERT를 흉내 낸다. created_at은 버전 순서를 흐트러뜨리지 않게 버전만큼 밀어 둔다. */
	private void insertRoute(
			String dispatchId,
			int version,
			String coordinates,
			String roadSegments,
			int estimatedDuration,
			String changeReason) {
		jdbc.update(
				"""
				INSERT INTO dispatch_route
				  (route_id, dispatch_id, route_version, route_coordinates, road_segments,
				   estimated_duration, change_reason, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, DATE_ADD('2026-11-20 00:00:00.000', INTERVAL ? SECOND))
				""",
				UUID.randomUUID().toString(),
				dispatchId,
				version,
				coordinates,
				roadSegments,
				estimatedDuration,
				changeReason,
				version);
	}
}
