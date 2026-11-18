package com.evacs.control.emergency.repository.jpa;

import com.evacs.control.emergency.domain.EmergencyRoute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyRouteJpaRepository extends JpaRepository<EmergencyRoute, UUID> {

	List<EmergencyRoute> findByEmergencyIdOrderBySequenceAsc(UUID emergencyId);
}
