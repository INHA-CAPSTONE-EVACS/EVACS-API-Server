package com.evacs.control.vehicle.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.vehicle.domain.VehicleState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Location Worker가 실제로 쓰는 {@code vehicle:{id}:state} Hash가 그대로 해석되는지 고정한다.
 * 이 계약이 깨지면 조회가 조용히 빈 목록을 돌려준다.
 *
 * <p>필드 이름·형식의 출처는 Location-Worker의 {@code apply-location.lua} HSET 블록이다
 * (lastMeasuredAt은 epoch milli, lastUpdatedAt은 ISO 8601 문자열).
 */
class VehicleStateParsingTest {

	private static Map<String, String> workerHash() {
		Map<String, String> hash = new LinkedHashMap<>();
		hash.put("vehicleId", "CAR_001");
		hash.put("vehicleType", "EMERGENCY");
		hash.put("latitude", "37.45");
		hash.put("longitude", "126.65");
		hash.put("speedMps", "12.5");
		hash.put("headingDegrees", "91.2");
		hash.put("h3CellId", "8930e0ab12fffff");
		hash.put("lastSequence", "42");
		hash.put("lastMeasuredAt", "1795424400000");
		hash.put("lastUpdatedAt", "2026-11-18T01:00:00.500Z");
		hash.put("stateVersion", "7");
		hash.put("status", "ACTIVE");
		hash.put("unknownFutureField", "123");
		return hash;
	}

	@Test
	@DisplayName("워커 상태 Hash를 VehicleState로 읽는다")
	void parsesWorkerStateHash() {
		VehicleState state = VehicleState.fromHash(workerHash());

		assertThat(state).isNotNull();
		assertThat(state.vehicleId()).isEqualTo("CAR_001");
		assertThat(state.isEmergency()).isTrue();
		assertThat(state.latitude()).isEqualTo(37.45);
		assertThat(state.speedMps()).isEqualTo(12.5);
		assertThat(state.h3CellId()).isEqualTo("8930e0ab12fffff");
		assertThat(state.lastSequence()).isEqualTo(42);
		assertThat(state.stateVersion()).isEqualTo(7);
		assertThat(state.measuredAt()).isEqualTo(Instant.ofEpochMilli(1795424400000L));
		assertThat(state.updatedAt()).isEqualTo(Instant.parse("2026-11-18T01:00:00.500Z"));
	}

	@Test
	@DisplayName("식별자나 좌표가 없으면 해석을 포기한다(한 건만 건너뛰게)")
	void rejectsIncompleteHash() {
		Map<String, String> noId = workerHash();
		noId.remove("vehicleId");
		assertThat(VehicleState.fromHash(noId)).isNull();

		Map<String, String> brokenCoordinate = workerHash();
		brokenCoordinate.put("latitude", "NaN이 아닌 쓰레기");
		assertThat(VehicleState.fromHash(brokenCoordinate)).isNull();
	}

	@Test
	@DisplayName("lastUpdatedAt이 기준보다 오래되면 stale로 본다")
	void detectsStaleByUpdatedAt() {
		VehicleState state = VehicleState.fromHash(workerHash());
		Instant now = Instant.parse("2026-11-18T01:00:30Z");

		assertThat(state.isStale(now, Duration.ofSeconds(5))).isTrue();
		assertThat(state.isStale(now, Duration.ofSeconds(120))).isFalse();
	}

	@Test
	@DisplayName("워커가 INACTIVE로 찍었으면 시각과 무관하게 stale이다")
	void inactiveStatusIsStale() {
		Map<String, String> hash = workerHash();
		hash.put("status", "INACTIVE");
		VehicleState state = VehicleState.fromHash(hash);

		assertThat(state.isStale(Instant.parse("2026-11-18T01:00:00.600Z"), Duration.ofMinutes(10)))
				.isTrue();
	}

	@Test
	@DisplayName("bounding box 밖의 차량은 걸러진다")
	void filtersByBoundingBox() {
		VehicleState state = VehicleState.fromHash(workerHash());

		assertThat(state.within(37.0, 38.0, 126.0, 127.0)).isTrue();
		assertThat(state.within(38.0, 39.0, 126.0, 127.0)).isFalse();
		assertThat(state.within(null, null, null, null)).isTrue();
	}

	/** 워커 키를 조립하는 설정 레코드. 오타 나면 조회가 전부 빈값이 된다. */
	@Test
	void buildsWorkerKeys() {
		EvacsProperties.Redis redis = new EvacsProperties.Redis(
				"test:",
				"test:evacs:emergency:%s:cone",
				"test:evacs:emergency:%s:alerted",
				"test:evacs:stream:emergency-event",
				10000);

		assertThat(redis.activeCellsKey()).isEqualTo("test:h3:cells:active");
		assertThat(redis.cellKeyPrefix()).isEqualTo("test:h3:vehicles:");
		assertThat(redis.vehicleStateKey("CAR_1")).isEqualTo("test:vehicle:CAR_1:state");
		assertThat(redis.coneKey("abc")).isEqualTo("test:evacs:emergency:abc:cone");
		assertThat(redis.alertKey("abc")).isEqualTo("test:evacs:emergency:abc:alerted");
	}

	/** 운영에서는 접두사가 비어 있어야 워커가 쓰는 키와 정확히 같다. */
	@Test
	void emptyPrefixMatchesWorkerKeys() {
		EvacsProperties.Redis redis = new EvacsProperties.Redis(null, "c:%s", "a:%s", "s", 10);

		assertThat(redis.activeCellsKey()).isEqualTo("h3:cells:active");
		assertThat(redis.cellKeyPrefix()).isEqualTo("h3:vehicles:");
		assertThat(redis.vehicleStateKey("CAR_1")).isEqualTo("vehicle:CAR_1:state");
	}
}
