package com.evacs.control.vehicle.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.vehicle.domain.VehicleState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ws-server가 실제로 쓰는 JSON(LocationUpdatedEvent)이 그대로 파싱되는지 확인한다.
 * 이 계약이 깨지면 조회가 조용히 빈 목록을 돌려주므로 고정해 둔다.
 */
class VehicleStateParsingTest {

	private final ObjectMapper mapper = new com.evacs.control.common.config.RedisConfig().evacsObjectMapper();

	@Test
	@DisplayName("ws-server의 위치 이벤트 JSON을 VehicleState로 읽는다")
	void parsesLocationUpdatedEvent() throws Exception {
		String json =
				"""
				{"eventId":"evt-1","vehicleId":"CAR_001","vehicleType":"EMERGENCY",
				 "latitude":37.45,"longitude":126.65,"speedMps":12.5,"headingDegrees":91.2,
				 "laneIndex":0,"laneCount":3,"measuredAt":"2026-11-18T01:00:00Z","sequence":42,
				 "occurredAt":"2026-11-18T01:00:00.500Z","unknownFutureField":123}
				""";

		VehicleState state = mapper.readValue(json, VehicleState.class);

		assertThat(state.vehicleId()).isEqualTo("CAR_001");
		assertThat(state.isEmergency()).isTrue();
		assertThat(state.laneIndex()).isZero();
		assertThat(state.sequence()).isEqualTo(42);
		assertThat(state.occurredAt()).isEqualTo(Instant.parse("2026-11-18T01:00:00.500Z"));
	}

	@Test
	@DisplayName("occurredAt이 기준보다 오래되면 stale로 본다")
	void detectsStale() throws Exception {
		String json = "{\"vehicleId\":\"CAR_1\",\"occurredAt\":\"2026-11-18T01:00:00Z\"}";
		VehicleState state = mapper.readValue(json, VehicleState.class);

		Instant now = Instant.parse("2026-11-18T01:01:00Z");
		assertThat(state.isStale(now, Duration.ofSeconds(30))).isTrue();
		assertThat(state.isStale(now, Duration.ofSeconds(120))).isFalse();
	}

	@Test
	@DisplayName("bounding box 밖의 차량은 걸러진다")
	void filtersByBoundingBox() {
		VehicleState state = new VehicleState(
				"e", "CAR_1", "NORMAL", 37.5, 126.9, 0, 0, null, null, Instant.now(), 1, Instant.now());

		assertThat(state.within(37.0, 38.0, 126.0, 127.0)).isTrue();
		assertThat(state.within(38.0, 39.0, 126.0, 127.0)).isFalse();
		assertThat(state.within(null, null, null, null)).isTrue();
	}

	/** 설정 레코드가 키 패턴을 제대로 채우는지(오타 나면 조회가 전부 빈값이 된다). */
	@Test
	void buildsRedisKeys() {
		EvacsProperties.Redis redis = new EvacsProperties.Redis(
				"evacs:vehicle:location",
				"evacs:emergency:%s:cone",
				"evacs:emergency:%s:alerted",
				"evacs:stream:emergency-event",
				10000);

		assertThat(redis.coneKey("abc")).isEqualTo("evacs:emergency:abc:cone");
		assertThat(redis.alertKey("abc")).isEqualTo("evacs:emergency:abc:alerted");
	}
}
