package com.evacs.control.vehicle.repository;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.vehicle.domain.VehicleCountSummary;
import com.evacs.control.vehicle.domain.VehicleState;
import com.evacs.control.vehicle.domain.VehicleType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Location Worker가 Redis에 정제해 둔 실시간 차량 상태를 읽는다.
 *
 * <p>워커 계약(Location-Worker design.md §2)에서 "지금 어디"의 출처는 세 키다.
 * {@code h3:cells:active}(SET)가 비어 있지 않은 셀을, {@code h3:vehicles:{cellId}}(ZSET)가
 * 셀 안의 차량과 수신 시각을, {@code vehicle:{vehicleId}:state}(Hash)가 좌표·속도·방향을 갖는다.
 * 상태 Hash는 갱신마다 EXPIRE 3600s이고 비활성 차량은 워커의 정리 주기가 ZSET에서 빼므로,
 * 읽는 쪽에서 오래된 항목을 지울 필요가 없다.
 *
 * <p>{@code stream:vehicle:state}는 읽지 않는다. 그 스트림의 payload에는 셀 변경 사실만 있고
 * 좌표·속도가 없어서(contracts.ts {@code VehicleStateUpdatedPayload}) 조회 응답을 채울 수 없다.
 * 그쪽은 Emergency Worker용 변경 로그다.
 *
 * <p>세 키를 따로 읽으면 왕복이 3회(SMEMBERS → 셀별 ZRANGE → 차량별 HGETALL)로 늘고 셀 집합과
 * 차량 상태의 시점이 어긋난다. 그래서 전부 Lua 스크립트 하나로 서버 사이드에서 돌려 왕복을
 * 1회로 줄였다. 대신 그 동안 Redis 단일 스레드를 점유하므로, 차량 규모가 커져 지연이 보이면
 * 셀을 쪼개 여러 번 EVAL 하도록 바꿔야 한다.
 */
@Repository
public class RedisVehicleRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisVehicleRepository.class);

	private final StringRedisTemplate redis;
	private final RedisScript<List> activeVehicleStatesScript;
	private final EvacsProperties properties;

	public RedisVehicleRepository(
			StringRedisTemplate redis,
			RedisScript<List> activeVehicleStatesScript,
			EvacsProperties properties) {
		this.redis = redis;
		this.activeVehicleStatesScript = activeVehicleStatesScript;
		this.properties = properties;
	}

	/** 조건에 맞는 차량 상태 목록 조회. */
	public List<VehicleState> findVehicles(VehicleQueryFilter filter) {
		Instant now = Instant.now();
		Duration activeTtl = activeTtl();
		List<VehicleState> result = new ArrayList<>();
		for (VehicleState state : readAll()) {
			if (!filter.includeStale() && state.isStale(now, activeTtl)) {
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

	/**
	 * 특정 차량의 최신 상태. 없으면 null.
	 *
	 * <p>셀 인덱스를 거치지 않고 상태 Hash를 직접 읽는다. 비활성 차량도 상태 Hash가 만료되기
	 * 전까지는 조회되고, 신선도는 호출자가 {@code isStale}로 판단한다.
	 */
	public VehicleState findById(String vehicleId) {
		Map<Object, Object> entries =
				redis.opsForHash().entries(properties.redis().vehicleStateKey(vehicleId));
		if (entries.isEmpty()) {
			return null;
		}
		return fromHashEntries(entries);
	}

	/** 일반차량·긴급차량별 활성 차량 수 집계. */
	public VehicleCountSummary countByType() {
		Instant now = Instant.now();
		Duration activeTtl = activeTtl();
		long normal = 0;
		long emergency = 0;
		long unknown = 0;
		long stale = 0;
		for (VehicleState state : readAll()) {
			if (state.isStale(now, activeTtl)) {
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
	 * 활성 셀에 들어 있는 모든 차량의 상태를 한 번의 EVAL로 읽는다.
	 *
	 * <p>스크립트는 신선도로 걸러 내지 않고 ZSET 멤버 전부를 돌려준다. 활성 판단 기준을 Java
	 * 한곳에 두어야 {@code includeStale=true} 조회와 요약의 stale 집계가 같은 규칙을 쓴다.
	 */
	@SuppressWarnings("unchecked")
	private List<VehicleState> readAll() {
		EvacsProperties.Redis keys = properties.redis();
		List<Object> rows = redis.execute(
				activeVehicleStatesScript,
				List.of(keys.activeCellsKey()),
				keys.cellKeyPrefix(),
				keys.vehicleStateKeyPrefix(),
				keys.vehicleStateKeySuffix());
		if (rows == null) {
			return List.of();
		}
		List<VehicleState> states = new ArrayList<>(rows.size());
		for (Object row : rows) {
			if (!(row instanceof List<?> fields)) {
				continue;
			}
			VehicleState state = toState(toHash((List<Object>) fields));
			if (state != null) {
				states.add(state);
			}
		}
		return states;
	}

	/** HGETALL 응답은 [field, value, field, value, ...] 평면 배열로 온다. */
	private static Map<String, String> toHash(List<Object> fields) {
		Map<String, String> hash = new LinkedHashMap<>();
		for (int i = 0; i + 1 < fields.size(); i += 2) {
			Object field = fields.get(i);
			Object value = fields.get(i + 1);
			if (field != null) {
				hash.put(field.toString(), value == null ? null : value.toString());
			}
		}
		return hash;
	}

	private static VehicleState fromHashEntries(Map<?, ?> entries) {
		Map<String, String> hash = new LinkedHashMap<>();
		entries.forEach((field, value) -> {
			if (field != null) {
				hash.put(field.toString(), value == null ? null : value.toString());
			}
		});
		return toState(hash);
	}

	/** 깨진 항목 하나가 조회 전체를 실패시키지 않도록 건너뛰고 로그만 남긴다. */
	private static VehicleState toState(Map<String, String> hash) {
		VehicleState state = VehicleState.fromHash(hash);
		if (state == null) {
			log.warn("차량 상태 Hash를 해석할 수 없어 건너뜁니다: fields={}", hash.keySet());
		}
		return state;
	}

	private Duration activeTtl() {
		return Duration.ofMillis(properties.vehicle().activeTtlMs());
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
