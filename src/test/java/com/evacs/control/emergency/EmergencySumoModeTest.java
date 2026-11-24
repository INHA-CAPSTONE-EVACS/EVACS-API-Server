package com.evacs.control.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.dto.EmergencyResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SUMO 모드 긴급상황 생성 검증.
 *
 * <p>SUMO 시뮬레이터(app.py) 대신 {@code /api/emergency} 하나만 받는 가짜 HTTP 서버를 띄워,
 * <b>커밋 후에 실제로 요청이 나가는지</b>와 본문 필드를 확인한다. 어댑터 연동에서 가장 쉽게
 *빠지는 부분이 "DB에는 썼는데 시뮬레이터 호출이 안 나간다"이므로 여기를 단정한다.
 *
 * <p>사전 조건: {@code docker compose -f docker/compose.yml up -d --wait}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmergencySumoModeTest {

	private static final BlockingQueue<String> RECEIVED = new ArrayBlockingQueue<>(10);

	private static HttpServer stub;

	@Autowired
	private TestRestTemplate http;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeAll
	static void startStub() throws IOException {
		stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/api/emergency", exchange -> {
			RECEIVED.offer(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"ok\":true,\"vehicleId\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		stub.start();
	}

	@AfterAll
	static void stopStub() {
		stub.stop(0);
	}

	/** 가짜 서버 포트는 런타임에 정해지므로 컨텍스트 기동 직전에 주입한다. */
	@DynamicPropertySource
	static void sumoProperties(DynamicPropertyRegistry registry) {
		registry.add("evacs.sumo.enabled", () -> true);
		registry.add("evacs.sumo.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
		registry.add("evacs.sumo.default-depot-id", () -> "yonghyeon");
	}

	@BeforeEach
	void clean() {
		RECEIVED.clear();
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		for (String table : List.of("outbox_event", "event_log", "emergency_route", "emergency")) {
			jdbc.execute("TRUNCATE TABLE " + table);
		}
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
	}

	@Test
	@DisplayName("isSumo=true면 생성 후 시뮬레이터에 차량 투입 요청이 나간다")
	void sumoModeSpawnsVehicle() throws Exception {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"EVACS_EMG_CALL_900", "FIRE", 37.4540, 126.6489, 37.4518, 126.6538,
				"SUMO 모드 테스트", null, true, "nambu");

		ResponseEntity<EmergencyResponse> created =
				http.postForEntity("/control/emergencies", dto, EmergencyResponse.class);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().isSumo()).isTrue();
		assertThat(jdbc.queryForObject("SELECT is_sumo FROM emergency WHERE id = ?", Boolean.class,
				created.getBody().id())).isTrue();

		String body = RECEIVED.poll(5, TimeUnit.SECONDS);
		assertThat(body).as("시뮬레이터 투입 요청이 나가지 않았다").isNotNull();
		Map<String, Object> json =
				objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
		assertThat(json).containsEntry("vehicleId", "EVACS_EMG_CALL_900");
		// 요청에 sumoDepotId를 줬으면 기본 차고지가 아니라 그 값을 쓴다.
		assertThat(json).containsEntry("depotId", "nambu");
		assertThat(json).containsEntry("destinationLat", 37.4518);
		assertThat(json).containsEntry("destinationLng", 126.6538);
		// 출발 좌표를 줬으면 그대로 넘겨 시뮬레이터가 차고지 대신 그 좌표에서 출발시킨다.
		assertThat(json).containsEntry("originLat", 37.4540);
		assertThat(json).containsEntry("originLng", 126.6489);
	}

	@Test
	@DisplayName("출발 좌표가 없으면 차고지 출발이라 originLat/Lng를 보내지 않는다")
	void sumoModeWithoutOriginSendsDepotOnly() throws Exception {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"EVACS_EMG_CALL_902", "FIRE", null, null, 37.4518, 126.6538,
				null, null, true, null);

		ResponseEntity<EmergencyResponse> created =
				http.postForEntity("/control/emergencies", dto, EmergencyResponse.class);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String body = RECEIVED.poll(5, TimeUnit.SECONDS);
		assertThat(body).as("시뮬레이터 투입 요청이 나가지 않았다").isNotNull();
		Map<String, Object> json =
				objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
		assertThat(json).doesNotContainKeys("originLat", "originLng");
		// sumoDepotId를 생략하면 설정 기본 차고지로 간다.
		assertThat(json).containsEntry("depotId", "yonghyeon");
	}

	@Test
	@DisplayName("isSumo 없으면 시뮬레이터를 호출하지 않는다")
	void realVehicleModeDoesNotCallSimulator() throws Exception {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"AMB_900", "FIRE", 37.45, 126.65, 37.46, 126.66, null, null, null, null);

		ResponseEntity<EmergencyResponse> created =
				http.postForEntity("/control/emergencies", dto, EmergencyResponse.class);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().isSumo()).isFalse();
		assertThat(RECEIVED.poll(1, TimeUnit.SECONDS)).isNull();
	}

	@Test
	@DisplayName("SUMO 모드인데 목적지가 없으면 400이고 아무것도 생성되지 않는다")
	void sumoModeWithoutDestinationIsBadRequest() {
		CreateEmergencyDto dto = new CreateEmergencyDto(
				"EVACS_EMG_CALL_901", "FIRE", 37.45, 126.65, null, null, null, null, true, null);

		ResponseEntity<String> response = http.postForEntity("/control/emergencies", dto, String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM emergency", Integer.class)).isZero();
	}
}
