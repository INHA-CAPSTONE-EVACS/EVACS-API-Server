package com.evacs.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EVACS 관제서버(Control API Server).
 *
 * <p>CQRS의 조회·명령 책임 분리를 기반으로 한다. 실시간 차량 위치/상태는 Redis에서 읽고,
 * 긴급상황 영속 데이터는 MySQL에 둔다. 긴급상황 생성·종료는 같은 트랜잭션에서
 * Outbox에 이벤트를 적고, {@code OutboxProcessor}가 이를 메시지 브로커로 발행한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ControlApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControlApiApplication.class, args);
	}
}
