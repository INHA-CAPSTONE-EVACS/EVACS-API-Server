package com.evacs.control.outbox.repository.jpa;

import com.evacs.control.outbox.domain.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxJpaRepository extends JpaRepository<OutboxEvent, UUID> {
}
