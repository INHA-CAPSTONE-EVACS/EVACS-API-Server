package com.evacs.control.alert.repository;

import com.evacs.control.alert.domain.VehicleAlert;
import com.evacs.control.common.config.EvacsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Alert Worker가 {@code evacs:stream:yield-alert}로 발행한 알림 이벤트를 읽는다.
 *
 * <p><b>소비자 그룹을 쓰지 않는다.</b> 그룹을 만들면 ws-server(그룹 {@code ws-server})와는
 * 분리되지만 관제 API가 ACK를 빠뜨리는 순간 PEL이 쌓이고, 운영 중 재시작마다 pending 복구를
 * 신경 써야 한다. 관제 화면은 "지금 어떤 차량에 알림이 떠 있는가"만 알면 되므로 커서(entry id)를
 * 들고 XREAD로 뒤만 따라 읽는다. 차량 전달 책임은 ws-server에 있고 이 경로는 읽기 전용이다.
 */
@Repository
public class RedisYieldAlertRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisYieldAlertRepository.class);

	/** 지원하는 Envelope 스키마 버전. 다른 버전은 로그만 남기고 버린다(ws-server와 같은 규칙). */
	private static final int SCHEMA_VERSION = 1;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final EvacsProperties properties;

	public RedisYieldAlertRepository(
			StringRedisTemplate redis, ObjectMapper evacsObjectMapper, EvacsProperties properties) {
		this.redis = redis;
		this.objectMapper = evacsObjectMapper;
		this.properties = properties;
	}

	/**
	 * 스트림의 최근 엔트리를 오래된 것부터 돌려준다. 기동 직후 현재 상태를 복원하는 데 쓴다.
	 *
	 * <p>XREVRANGE로 뒤에서 세어 오는 이유는 스트림 전체가 수만 건일 수 있어서다 —
	 * 처음부터 재생하면 기동이 느려지고 이미 해제된 옛 알림까지 훑는다.
	 */
	public List<VehicleAlert> findRecent(int count) {
		String stream = properties.alert().stream();
		List<MapRecord<String, Object, Object>> records =
				redis.opsForStream().reverseRange(stream, Range.unbounded(), Limit.limit().count(count));
		if (records == null || records.isEmpty()) {
			return List.of();
		}
		List<MapRecord<String, Object, Object>> ordered = new ArrayList<>(records);
		Collections.reverse(ordered); // XREVRANGE는 최신이 먼저다 → 적용 순서를 시간순으로 되돌린다
		return parseAll(ordered);
	}

	/** 커서 이후에 추가된 엔트리를 시간순으로 돌려준다. */
	public List<VehicleAlert> findAfter(String entryId, int count) {
		String stream = properties.alert().stream();
		List<MapRecord<String, Object, Object>> records =
				redis.opsForStream()
						.read(
								StreamReadOptions.empty().count(count),
								StreamOffset.create(stream, ReadOffset.from(entryId)));
		if (records == null || records.isEmpty()) {
			return List.of();
		}
		return parseAll(records);
	}

	private List<VehicleAlert> parseAll(List<MapRecord<String, Object, Object>> records) {
		List<VehicleAlert> alerts = new ArrayList<>(records.size());
		for (MapRecord<String, Object, Object> record : records) {
			VehicleAlert alert = parse(record);
			if (alert != null) {
				alerts.add(alert);
			}
		}
		return alerts;
	}

	/** 엔트리 하나를 해석한다. 해석할 수 없으면 null(로그 후 건너뛴다). */
	private VehicleAlert parse(MapRecord<String, Object, Object> record) {
		String entryId = record.getId() == null ? null : record.getId().getValue();
		String raw = value(record.getValue(), "event");
		if (raw == null) {
			log.warn("알림 엔트리에 event 필드가 없습니다: entryId={}", entryId);
			return null;
		}
		JsonNode envelope;
		try {
			envelope = objectMapper.readTree(raw);
		} catch (Exception e) {
			log.warn("알림 Envelope 파싱 실패: entryId={} error={}", entryId, e.getMessage());
			return null;
		}
		String eventType = text(envelope, "eventType");
		if (eventType == null) {
			log.warn("알림 Envelope에 eventType이 없습니다: entryId={}", entryId);
			return null;
		}
		int schemaVersion = envelope.path("schemaVersion").asInt(SCHEMA_VERSION);
		if (schemaVersion != SCHEMA_VERSION) {
			log.warn("지원하지 않는 알림 스키마 버전: entryId={} schemaVersion={}", entryId, schemaVersion);
			return null;
		}
		JsonNode payload = envelope.path("payload");
		// 라우팅 키는 설계서의 targetVehicleId고, 없으면 vehicleId다(ws-server와 같은 규칙).
		String vehicleId = text(payload, "targetVehicleId");
		if (vehicleId == null) {
			vehicleId = text(payload, "vehicleId");
		}
		if (vehicleId == null) {
			log.warn("알림 payload에 대상 차량이 없습니다: entryId={} eventType={}", entryId, eventType);
			return null;
		}
		return new VehicleAlert(
				vehicleId,
				eventType,
				text(payload, "alertId"),
				payload.hasNonNull("alertVersion") ? payload.get("alertVersion").asInt() : null,
				text(payload, "dispatchId"),
				text(payload, "approachDirection"),
				text(payload, "recommendedAction"),
				text(payload, "message"),
				text(payload, "reason"),
				instant(text(envelope, "occurredAt")),
				instant(text(payload, "expiresAt")),
				text(envelope, "eventId"),
				entryId);
	}

	private static String value(Map<Object, Object> fields, String key) {
		Object raw = fields == null ? null : fields.get(key);
		if (raw == null) {
			return null;
		}
		String text = String.valueOf(raw);
		return text.isBlank() ? null : text;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isValueNode() || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text.isBlank() ? null : text;
	}

	/** 시각 형식이 어긋난 이벤트 하나 때문에 알림 전체를 버리지 않는다. */
	private static Instant instant(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Instant.parse(raw);
		} catch (Exception e) {
			log.warn("알림 시각 파싱 실패: value={}", raw);
			return null;
		}
	}
}
