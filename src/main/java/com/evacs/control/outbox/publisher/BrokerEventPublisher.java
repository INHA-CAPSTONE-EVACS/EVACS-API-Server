package com.evacs.control.outbox.publisher;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.outbox.domain.OutboxEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link EventPublisher}의 Redis Streams 구현.
 *
 * <p>evacs-ws-server와 같은 브로커(Redis Streams)를 쓴다. Pub/Sub이 아닌 이유는 소비자인
 * Emergency Worker가 ack과 재전달을 필요로 하기 때문이다 — 긴급상황 생성/종료는 유실되면
 * 안 되는 "명령"이다.
 *
 * <p>필드를 JSON 한 덩어리로 넣지 않고 eventId/eventType/aggregateId를 분리해 둔 것은
 * Worker가 payload를 파싱하기 전에 분기하고 멱등 키를 뽑을 수 있게 하기 위해서다.
 *
 * <p>트림은 걸지 않는다. {@code XADD MAXLEN}은 소비자 그룹의 PEL/lag을 보지 않고 자르기 때문에
 * 아직 읽지 않은 엔트리가 조용히 사라진다(그 유실은 XINFO GROUPS에도 안 잡힌다).
 * 보존이 필요해지면 길이가 아니라 시간 기준 {@code XTRIM MINID}로 따로 돌려야 한다.
 */
@Component
public class BrokerEventPublisher implements EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(BrokerEventPublisher.class);

	/** 이벤트 봉투(스트림 필드)의 버전. payload 구조를 바꿀 때만 올린다. */
	private static final String SCHEMA_VERSION = "1";
	/** 발행 주체. Worker가 여러 생산자를 구분하는 데 쓴다. */
	private static final String PRODUCER = "control-api";

	private final StringRedisTemplate redis;
	private final EvacsProperties properties;

	public BrokerEventPublisher(StringRedisTemplate redis, EvacsProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	@Override
	public String publish(OutboxEvent event) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", event.getId().toString());
		fields.put("eventType", event.getEventType());
		// 스키마 메타는 이벤트 종류와 무관하게 모든 긴급상황 이벤트(생성·종료)에 같은 위치로 넣는다.
		// 종류마다 다르면 Worker가 종료 이벤트에서만 메타를 못 찾아 분기가 갈린다.
		fields.put("schemaVersion", SCHEMA_VERSION);
		fields.put("producer", PRODUCER);
		fields.put("aggregateType", event.getAggregateType());
		fields.put("aggregateId", event.getAggregateId());
		fields.put("occurredAt", event.getCreatedAt().toString());
		fields.put("payload", event.getPayload());

		String stream = properties.redis().emergencyStream();
		RecordId id = redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(stream));
		if (id == null) {
			throw new IllegalStateException("브로커가 메시지 ID를 반환하지 않았습니다: " + stream);
		}
		log.debug("긴급상황 이벤트 발행: type={} id={} messageId={}", event.getEventType(), event.getId(), id);
		return id.getValue();
	}
}
