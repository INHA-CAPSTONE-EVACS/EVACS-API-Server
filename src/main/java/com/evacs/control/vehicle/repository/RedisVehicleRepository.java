package com.evacs.control.vehicle.repository;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.vehicle.domain.VehicleCountSummary;
import com.evacs.control.vehicle.domain.VehicleState;
import com.evacs.control.vehicle.domain.VehicleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Location Worker(evacs-ws-server)가 Redis에 저장한 실시간 차량 데이터에 접근한다.
 *
 * <p>읽는 것은 스트림이 아니라 차량별 최신 위치 스냅샷 해시다. 키 하나에 차량당 필드 1개이므로
 * "지금 어디 있나"는 {@code HGET}/{@code HGETALL} 한 번으로 끝난다. 스트림을 되감으면
 * 같은 질문에 훨씬 비싼 비용을 치르게 되고, 소비자 그룹이 필요 없는 조회에 ack 부담까지 생긴다.
 *
 * <p>해시 필드에는 TTL이 없어 떠난 차량의 마지막 위치가 남는다. 신선도 판단은 여기서 하지 않고
 * {@code occurredAt}을 그대로 올려 보내 서비스 계층이 정책을 적용한다 — 다만 목록/집계 기본값은
 * "오래된 것 제외"다.
 */
@Repository
public class RedisVehicleRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisVehicleRepository.class);

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final EvacsProperties properties;

	public RedisVehicleRepository(
			StringRedisTemplate redis, ObjectMapper evacsObjectMapper, EvacsProperties properties) {
		this.redis = redis;
		this.objectMapper = evacsObjectMapper;
		this.properties = properties;
	}

	/** 조건에 맞는 차량 상태 목록 조회. */
	public List<VehicleState> findVehicles(VehicleQueryFilter filter) {
		Instant now = Instant.now();
		Duration staleAfter = staleAfter();
		List<VehicleState> result = new ArrayList<>();
		for (VehicleState state : readAll()) {
			if (!filter.includeStale() && state.isStale(now, staleAfter)) {
				continue;
			}
			if (filter.type() != null && state.type() != filter.type()) {
				continue;
			}
			if (filter.typeFilterPresent() && filter.type() == null) {
				// 해석할 수 없는 유형으로 필터링하면 결과가 없어야 한다.
				continue;
			}
			if (!state.within(filter.minLat(), filter.maxLat(), filter.minLng(), filter.maxLng())) {
				continue;
			}
			result.add(state);
		}
		result.sort((a, b) -> a.vehicleId().compareTo(b.vehicleId()));
		return result;
	}

	/** 특정 차량의 최신 상태. 없으면 null. */
	public VehicleState findById(String vehicleId) {
		Object raw;
		try {
			raw = redis.opsForHash().get(snapshotKey(), vehicleId);
		} catch (DataAccessException e) {
			throw snapshotKeyTypeError(e);
		}
		return raw == null ? null : parse(vehicleId, raw.toString());
	}

	/** 일반차량·긴급차량별 활성 차량 수 집계. */
	public VehicleCountSummary countByType() {
		Instant now = Instant.now();
		Duration staleAfter = staleAfter();
		long normal = 0;
		long emergency = 0;
		long unknown = 0;
		long stale = 0;
		for (VehicleState state : readAll()) {
			if (state.isStale(now, staleAfter)) {
				stale++;
				continue;
			}
			VehicleType type = state.type();
			if (type == VehicleType.EMERGENCY) {
				emergency++;
			} else if (type == VehicleType.NORMAL) {
				normal++;
			} else {
				unknown++;
			}
		}
		return new VehicleCountSummary(normal + emergency + unknown, normal, emergency, unknown, stale);
	}

	/**
	 * 스냅샷 해시 전체를 읽어 파싱한다.
	 *
	 * <p>차량 수 규모(수천 대)에서는 HGETALL 한 번이 가장 싸다. 더 커지면 {@code HSCAN}으로
	 * 끊어 읽도록 바꿔야 한다 — HGETALL은 그동안 Redis 단일 스레드를 점유한다.
	 */
	private List<VehicleState> readAll() {
		Map<Object, Object> entries;
		try {
			entries = redis.opsForHash().entries(snapshotKey());
		} catch (DataAccessException e) {
			throw snapshotKeyTypeError(e);
		}
		List<VehicleState> states = new ArrayList<>(entries.size());
		for (Map.Entry<Object, Object> entry : entries.entrySet()) {
			VehicleState state = parse(entry.getKey().toString(), entry.getValue().toString());
			if (state != null) {
				states.add(state);
			}
		}
		return states;
	}

	/** 깨진 항목 하나가 조회 전체를 실패시키지 않도록 건너뛰고 로그만 남긴다. */
	private VehicleState parse(String vehicleId, String json) {
		try {
			return objectMapper.readValue(json, VehicleState.class);
		} catch (Exception e) {
			log.warn("차량 스냅샷 파싱 실패, 건너뜁니다: vehicleId={} error={}", vehicleId, e.getMessage());
			return null;
		}
	}

	private String snapshotKey() {
		return properties.redis().locationSnapshotKey();
	}

	/**
	 * WRONGTYPE은 키 이름을 잘못 설정했다는 신호다.
	 *
	 * <p>ws-server는 위치를 두 곳에 쓴다: 스트림(XADD, {@code EVACS_LOCATION_STREAM})과
	 * 스냅샷 해시(HSET, {@code EVACS_LOCATION_SNAPSHOT_KEY}). 관제 API는 해시만 읽어야 하는데
	 * 설정에 스트림 키가 들어가면 HGETALL이 WRONGTYPE으로 거절된다. 원문 스택만 보고는
	 * 어느 키가 문제인지 알 수 없으므로 키 이름과 실제 타입을 메시지에 박아 둔다.
	 */
	private RuntimeException snapshotKeyTypeError(DataAccessException cause) {
		String key = snapshotKey();
		if (!isWrongType(cause)) {
			return cause;
		}
		String actualType;
		try {
			DataType type = redis.type(key);
			actualType = type == null ? "unknown" : type.code();
		} catch (DataAccessException ignored) {
			actualType = "unknown";
		}
		log.error(
				"차량 스냅샷 키가 Hash가 아닙니다: key={} actualType={}. "
						+ "evacs.redis.location-snapshot-key(EVACS_LOCATION_SNAPSHOT_KEY)에는 "
						+ "ws-server가 HSET하는 스냅샷 해시 키(기본 evacs:vehicle:location)를 넣어야 합니다. "
						+ "위치 스트림(EVACS_LOCATION_STREAM) 키를 넣으면 이 오류가 납니다.",
				key,
				actualType);
		return new IllegalStateException(
				"차량 스냅샷 키 '%s'의 타입이 %s입니다. 스냅샷 해시 키로 바꿔 주세요.".formatted(key, actualType),
				cause);
	}

	/** 메시지가 래퍼 예외에 가려지므로 cause 체인을 따라 WRONGTYPE을 찾는다. */
	private static boolean isWrongType(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (String.valueOf(t.getMessage()).contains("WRONGTYPE")) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private Duration staleAfter() {
		return Duration.ofSeconds(properties.vehicle().staleAfterSeconds());
	}

	/**
	 * 저장소가 이해하는 조회 조건. 웹 계층의 DTO를 그대로 받지 않도록 분리해 둔다.
	 *
	 * @param typeFilterPresent 유형 필터가 들어왔는지. 값은 들어왔지만 해석 불가인 경우를
	 *                          "조건 없음"과 구분하기 위해 필요하다.
	 */
	public record VehicleQueryFilter(
			Double minLat,
			Double maxLat,
			Double minLng,
			Double maxLng,
			VehicleType type,
			boolean typeFilterPresent,
			boolean includeStale) {
	}
}
