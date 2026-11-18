package com.evacs.control.emergency.repository.jpa;

import com.evacs.control.emergency.domain.Emergency;
import com.evacs.control.emergency.domain.EmergencyStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 어댑터. 애플리케이션 코드는 이 인터페이스가 아니라
 * {@code EmergencyRepository}(설계서 이름)를 통해 접근한다.
 */
public interface EmergencyJpaRepository
		extends JpaRepository<Emergency, UUID>, JpaSpecificationExecutor<Emergency> {

	Optional<Emergency> findFirstByVehicleIdAndStatus(String vehicleId, EmergencyStatus status);
}
