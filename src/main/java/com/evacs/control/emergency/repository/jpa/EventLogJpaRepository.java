package com.evacs.control.emergency.repository.jpa;

import com.evacs.control.emergency.domain.EventLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventLogJpaRepository extends JpaRepository<EventLog, UUID> {

	List<EventLog> findByEmergencyIdOrderByOccurredAtDesc(UUID emergencyId);
}
