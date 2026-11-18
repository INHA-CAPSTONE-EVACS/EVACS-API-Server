package com.evacs.control.emergency.repository;

import com.evacs.control.emergency.domain.EventLog;
import com.evacs.control.emergency.repository.jpa.EventLogJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 긴급상황에 따라 발생한 이벤트 전송 이력을 조회한다.
 *
 * <p>쓰기는 Emergency Worker 쪽 책임이라 조회만 둔다.
 */
@Repository
public class EventLogRepository {

	private final EventLogJpaRepository jpa;

	public EventLogRepository(EventLogJpaRepository jpa) {
		this.jpa = jpa;
	}

	/** 특정 긴급상황과 관련된 이벤트 전송 이력 조회(최신 순). */
	public List<EventLog> findByEmergencyId(UUID emergencyId) {
		return jpa.findByEmergencyIdOrderByOccurredAtDesc(emergencyId);
	}
}
