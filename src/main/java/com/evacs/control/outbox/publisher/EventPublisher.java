package com.evacs.control.outbox.publisher;

import com.evacs.control.outbox.domain.OutboxEvent;

/**
 * 메시지 브로커 발행 기능에 대한 공통 계약.
 *
 * <p>인터페이스로 둔 이유는 브로커 교체(Redis Streams → Kafka 등)가 OutboxProcessor를 건드리지
 * 않게 하기 위해서다. 구현체는 발행 성공이 확인된 뒤에만 정상 반환해야 한다 —
 * 던지지 않고 돌아오면 프로세서가 PUBLISHED로 기록한다.
 */
public interface EventPublisher {

	/**
	 * 브로커에 이벤트를 발행하고 성공 확인을 기다린다.
	 *
	 * @return 브로커가 돌려준 메시지 ID(추적용)
	 * @throws RuntimeException 발행 실패. 프로세서가 재시도 대상으로 기록한다.
	 */
	String publish(OutboxEvent event);
}
