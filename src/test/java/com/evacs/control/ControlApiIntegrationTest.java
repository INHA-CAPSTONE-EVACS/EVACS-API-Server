package com.evacs.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.common.geo.H3Support;
import com.evacs.control.emergency.dto.AlertedVehiclesResponse;
import com.evacs.control.emergency.dto.ConeResponse;
import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.dto.EmergencyListResponse;
import com.evacs.control.emergency.dto.EmergencyResponse;
import com.evacs.control.emergency.dto.EmergencyRouteResponse;
import com.evacs.control.vehicle.dto.VehicleListResponse;
import com.evacs.control.vehicle.dto.VehicleSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 설계서 2절의 비즈니스 로직을 실제 MySQL·Redis에 대고 검증한다.
 *
 * <p>사전 조건: {@code docker compose -f docker/compose.yml up -d --wait}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ControlApiIntegrationTest {

	private static final String KEY_PREFIX = "test:";
	private static final String ACTIVE_CELLS_KEY = KEY_PREFIX + "h3:cells:active";
	private static final String STREAM_KEY = "test:evacs:stream:emergency-event";

	/** 워커가 쓰는 셀 해상도(Location-Worker H3_RESOLUTION 기본값). */
	private static final int CELL_RESOLUTION = 9;

	@Autowired
	private TestRestTemplate http;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private H3Support h3;

	@BeforeEach
	void clean() {
		// MySQL의 TRUNCATE는 다중 테이블·CASCADE를 지원하지 않고 FK가 걸린 테이블도 거부한다.
		// 테스트 정리 구간에서만 FK 체크를 끄고 테이블별로 비운다.
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		for (String table : List.of("outbox_event", "event_log", "emergency_route", "emergency")) {
			jdbc.execute("TRUNCATE TABLE " + table);
		}
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
		redis.delete(STREAM_KEY);
		// 워커 키(셀 인덱스·차량 상태)를 테스트 접두사 범위에서만 지운다.
		for (String pattern : List.of(KEY_PREFIX + "h3:*", KEY_PREFIX + "vehicle:*")) {
			redis.keys(pattern).forEach(redis::delete);
		}
		redis.keys("test:evacs:emergency:*").forEach(redis::delete);
	}

	// ---------- 실시간 차량 정보 조회 ----------

	@Test
	@DisplayName("Location Worker가 쓴 상태 Hash·셀 인덱스를 읽어 목록·요약·밀도를 만든다")
	void readsVehicleSnapshot() {
		putVehicle("CAR_001", "NORMAL", 37.4500, 126.6500, Instant.now());
		putVehicle("CAR_002", "NORMAL", 37.4501, 126.6501, Instant.now());
		putVehicle("AMB_001", "EMERGENCY", 37.5500, 126.7500, Instant.now());
		// 워커의 정리 주기가 아직 돌지 않아 셀 ZSET에 남은 차량은 활성에서 빠져야 한다.
		putVehicle("GHOST", "NORMAL", 37.4000, 126.6000, Instant.now().minus(Duration.ofMinutes(10)));

		VehicleListResponse list = http.getForObject("/control/vehicles", VehicleListResponse.class);
		assertThat(list.count()).isEqualTo(3);
		assertThat(list.vehicles()).extracting("vehicleId").doesNotContain("GHOST");

		VehicleSummaryResponse summary =
				http.getForObject("/control/vehicles/summary", VehicleSummaryResponse.class);
		assertThat(summary.total()).isEqualTo(3);
		assertThat(summary.emergency()).isEqualTo(1);
		assertThat(summary.stale()).isEqualTo(1);

		VehicleListResponse onlyEmergency =
				http.getForObject("/control/vehicles?vehicleType=EMERGENCY", VehicleListResponse.class);
		assertThat(onlyEmergency.count()).isEqualTo(1);

		// bounding box 밖은 걸러진다.
		VehicleListResponse boxed = http.getForObject(
				"/control/vehicles?minLat=37.44&maxLat=37.46&minLng=126.64&maxLng=126.66",
				VehicleListResponse.class);
		assertThat(boxed.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("없는 차량은 404")
	void unknownVehicleIs404() {
		ResponseEntity<String> response = http.getForEntity("/control/vehicles/NOPE", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---------- 긴급상황 생성/조회/종료 + Outbox ----------

	@Test
	@DisplayName("생성하면 201과 함께 경로가 저장되고 Outbox 이벤트가 브로커로 발행된다")
	void createPublishesOutboxEvent() throws Exception {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"AMB_001",
				"FIRE",
				37.45,
				126.65,
				37.55,
				126.75,
				"테스트 출동",
				List.of(
						new CreateEmergencyDto.RoutePoint(37.45, 126.65, 0, 0),
						new CreateEmergencyDto.RoutePoint(37.50, 126.70, 1, 120)));

		ResponseEntity<EmergencyResponse> created =
				http.postForEntity("/control/emergencies", dto, EmergencyResponse.class);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String id = created.getBody().id();
		assertThat(created.getBody().status()).isEqualTo("ACTIVE");

		EmergencyRouteResponse route =
				http.getForObject("/control/emergencies/{id}/route", EmergencyRouteResponse.class, id);
		assertThat(route.pointCount()).isEqualTo(2);
		assertThat(route.points().get(0).h3Index()).isNotBlank();

		// OutboxProcessor가 비동기로 발행한다. 스트림에 들어올 때까지 기다린다.
		MapRecord<String, Object, Object> record = awaitStreamRecord();
		assertThat(record.getValue()).containsEntry("eventType", "EMERGENCY_CREATED");
		assertThat(record.getValue()).containsEntry("aggregateId", id);

		Integer published = awaitPublishedCount(1);
		assertThat(published).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 차량에 진행 중 긴급상황이 있으면 409")
	void duplicateActiveEmergencyIsConflict() {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"AMB_002", "RESCUE", 37.45, 126.65, null, null, null, null);
		assertThat(http.postForEntity("/control/emergencies", dto, EmergencyResponse.class).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> second = http.postForEntity("/control/emergencies", dto, String.class);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("종료하면 ENDED가 되고 두 번째 종료는 409")
	void endEmergencyTransitionsOnce() {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"AMB_003", "FIRE", 37.45, 126.65, null, null, null, null);
		String id = http.postForEntity("/control/emergencies", dto, EmergencyResponse.class)
				.getBody()
				.id();

		ResponseEntity<EmergencyResponse> ended =
				http.postForEntity("/control/emergencies/{id}/end", null, EmergencyResponse.class, id);
		assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ended.getBody().status()).isEqualTo("ENDED");
		assertThat(ended.getBody().endedAt()).isNotNull();

		ResponseEntity<String> again =
				http.postForEntity("/control/emergencies/{id}/end", null, String.class, id);
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// 종료 후에는 같은 차량으로 다시 생성할 수 있어야 한다.
		assertThat(http.postForEntity("/control/emergencies", dto, EmergencyResponse.class).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	@DisplayName("status 필터로 목록을 거른다")
	void filtersEmergencyList() {
		http.postForEntity(
				"/control/emergencies",
				new CreateEmergencyDto("AMB_004", "FIRE", 37.4, 126.6, null, null, null, null),
				EmergencyResponse.class);
		String endedId = http.postForEntity(
						"/control/emergencies",
						new CreateEmergencyDto("AMB_005", "FIRE", 37.4, 126.6, null, null, null, null),
						EmergencyResponse.class)
				.getBody()
				.id();
		http.postForEntity("/control/emergencies/{id}/end", null, EmergencyResponse.class, endedId);

		assertThat(http.getForObject("/control/emergencies", EmergencyListResponse.class).count())
				.isEqualTo(2);
		assertThat(http.getForObject("/control/emergencies?status=ACTIVE", EmergencyListResponse.class)
						.count())
				.isEqualTo(1);
		assertThat(http.getForObject("/control/emergencies?status=ENDED", EmergencyListResponse.class)
						.count())
				.isEqualTo(1);
	}

	// ---------- Worker가 Redis에 써 둔 cone / 알림 수신 ----------

	@Test
	@DisplayName("cone이 없으면 404가 아니라 available=false")
	void missingConeIsNotAnError() {
		String id = http.postForEntity(
						"/control/emergencies",
						new CreateEmergencyDto("AMB_006", "FIRE", 37.4, 126.6, null, null, null, null),
						EmergencyResponse.class)
				.getBody()
				.id();

		ConeResponse cone = http.getForObject("/control/emergencies/{id}/cone", ConeResponse.class, id);
		assertThat(cone.available()).isFalse();
		assertThat(cone.cells()).isEmpty();
	}

	@Test
	@DisplayName("Worker가 쓴 cone/알림 수신 차량을 읽는다")
	void readsWorkerWrittenState() {
		String id = http.postForEntity(
						"/control/emergencies",
						new CreateEmergencyDto("AMB_007", "FIRE", 37.4, 126.6, null, null, null, null),
						EmergencyResponse.class)
				.getBody()
				.id();

		// 8930e0ab12fffff = (37.45, 126.65)의 해상도 9 셀. 유효하지 않은 셀은 응답에서 걸러진다.
		redis.opsForValue()
				.set(
						"test:evacs:emergency:" + id + ":cone",
						"""
						{"emergencyId":"%s","vehicleId":"AMB_007","version":7,"resolution":9,
						 "cells":["8930e0ab12fffff","not-a-cell"],"updatedAt":"2026-11-18T01:00:00Z"}
						"""
								.formatted(id));
		redis.opsForSet().add("test:evacs:emergency:" + id + ":alerted", "CAR_001", "CAR_002");

		ConeResponse cone = http.getForObject("/control/emergencies/{id}/cone", ConeResponse.class, id);
		assertThat(cone.available()).isTrue();
		assertThat(cone.version()).isEqualTo(7);
		// 깨진 셀은 조용히 빠지고 유효한 셀만 중심좌표와 함께 나온다.
		assertThat(cone.cells()).hasSize(1);
		assertThat(cone.cells().get(0).h3Index()).isEqualTo("8930e0ab12fffff");
		assertThat(cone.cells().get(0).centerLat()).isCloseTo(37.4503, org.assertj.core.data.Offset.offset(0.01));

		AlertedVehiclesResponse alerted =
				http.getForObject("/control/emergencies/{id}/alerted-vehicles", AlertedVehiclesResponse.class, id);
		assertThat(alerted.count()).isEqualTo(2);
		assertThat(alerted.vehicleIds()).containsExactly("CAR_001", "CAR_002");
	}

	@Test
	@DisplayName("ID 형식이 틀리면 400, 없는 ID면 404")
	void invalidAndMissingIds() {
		assertThat(http.getForEntity("/control/emergencies/not-a-uuid", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(http.getForEntity(
								"/control/emergencies/00000000-0000-0000-0000-000000000000", String.class)
						.getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("vehicleId 없는 생성 요청은 400")
	void validationRejectsEmptyVehicleId() {
		ResponseEntity<String> response = http.postForEntity(
				"/control/emergencies",
				new CreateEmergencyDto("", "FIRE", null, null, null, null, null, null),
				String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ---------- helpers ----------

	/**
	 * Location Worker의 apply-location.lua가 쓰는 세 키를 그대로 흉내 낸다.
	 * (상태 Hash + 셀 ZSET score=수신 시각 ms + 활성 셀 SET)
	 */
	private void putVehicle(String vehicleId, String type, double lat, double lng, Instant updatedAt) {
		String cell = h3.cellOf(lat, lng, CELL_RESOLUTION);
		Map<String, String> state = new LinkedHashMap<>();
		state.put("vehicleId", vehicleId);
		state.put("vehicleType", type);
		state.put("latitude", Double.toString(lat));
		state.put("longitude", Double.toString(lng));
		state.put("speedMps", "10.0");
		state.put("headingDegrees", "90.0");
		state.put("h3CellId", cell);
		state.put("lastSequence", "1");
		state.put("lastMeasuredAt", Long.toString(updatedAt.toEpochMilli()));
		state.put("lastUpdatedAt", updatedAt.toString());
		state.put("stateVersion", "1");
		state.put("status", "ACTIVE");

		redis.opsForHash().putAll(KEY_PREFIX + "vehicle:" + vehicleId + ":state", state);
		redis.opsForZSet()
				.add(KEY_PREFIX + "h3:vehicles:" + cell, vehicleId, updatedAt.toEpochMilli());
		redis.opsForSet().add(ACTIVE_CELLS_KEY, cell);
	}

	/**
	 * markPublished는 스트림 XADD 직후 별 트랜잭션에서 커밋된다. 스트림에 레코드가 보인 순간
	 * 아직 PUBLISHED가 안 찍혀 있을 수 있으므로 기대 개수가 될 때까지 기다린다.
	 */
	private Integer awaitPublishedCount(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		Integer count = 0;
		while (System.nanoTime() < deadline) {
			count = jdbc.queryForObject(
					"SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class);
			if (count != null && count >= expected) {
				return count;
			}
			Thread.sleep(100);
		}
		return count;
	}

	/** 발행은 비동기다. 최대 10초까지 스트림을 들여다본다. */
	private MapRecord<String, Object, Object> awaitStreamRecord() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			List<MapRecord<String, Object, Object>> records =
					redis.opsForStream().range(STREAM_KEY, org.springframework.data.domain.Range.unbounded());
			if (records != null && !records.isEmpty()) {
				return records.get(0);
			}
			Thread.sleep(100);
		}
		throw new AssertionError("브로커 스트림에 이벤트가 발행되지 않았습니다: " + STREAM_KEY);
	}
}
