package com.evacs.control.outbox.processor;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.outbox.domain.OutboxEvent;
import com.evacs.control.outbox.publisher.EventPublisher;
import com.evacs.control.outbox.repository.OutboxRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 발행 대기 중인 Outbox 이벤트를 조회하고 메시지 브로커 발행 과정을 조정한다.
 *
 * <p>트랜잭션을 걸지 않는다. 선점·상태 갱신은 각각 짧은 독립 트랜잭션이고, 그 사이의
 * 브로커 발행은 트랜잭션 밖에서 한다. 발행(네트워크 I/O)을 트랜잭션 안에 넣으면 브로커가
 * 느려질 때 DB 커넥션 풀이 먼저 마른다.
 *
 * <p>중복 발행은 설계상 허용한다(at-least-once). 발행에 성공한 직후 markPublished 전에
 * 프로세스가 죽으면 같은 이벤트가 다시 나간다 — 소비자가 eventId로 멱등 처리해야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "evacs.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxProcessor {

	private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

	private final OutboxRepository outboxRepository;
	private final EventPublisher eventPublisher;
	private final EvacsProperties properties;

	public OutboxProcessor(
			OutboxRepository outboxRepository, EventPublisher eventPublisher, EvacsProperties properties) {
		this.outboxRepository = outboxRepository;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
	}

	/**
	 * 발행 대기 이벤트를 조회하고 순차적으로 발행 처리.
	 *
	 * <p>fixedDelay라 이전 주기가 끝난 뒤에 다음 주기가 시작된다. fixedRate로 두면 발행이
	 * 느릴 때 주기가 겹쳐 같은 이벤트를 두 스레드가 다투게 된다.
	 */
	@Scheduled(fixedDelayString = "${evacs.outbox.poll-interval-ms:1000}")
	public void processPending() {
		List<OutboxEvent> events;
		try {
			events = outboxRepository.claimPending(properties.outbox().batchSize());
		} catch (Exception e) {
			// DB가 잠깐 끊긴 경우. 다음 주기에 다시 시도하면 되므로 스케줄러를 죽이지 않는다.
			log.warn("Outbox 선점 실패: {}", e.getMessage());
			return;
		}
		if (events.isEmpty()) {
			return;
		}
		log.debug("Outbox 이벤트 {}건 선점", events.size());
		for (OutboxEvent event : events) {
			publishEvent(event);
		}
	}

	/** 단일 이벤트 발행 및 발행 결과에 따른 상태 갱신. */
	public void publishEvent(OutboxEvent event) {
		try {
			String messageId = eventPublisher.publish(event);
			outboxRepository.markPublished(event.getId(), messageId);
		} catch (Exception e) {
			log.warn("이벤트 발행 실패: id={} attempts={} error={}", event.getId(), event.getAttempts(), e.toString());
			outboxRepository.markFailed(event.getId(), e.toString(), properties.outbox().maxAttempts());
		}
	}
}
