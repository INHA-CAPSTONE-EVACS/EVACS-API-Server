package com.evacs.control.emergency.repository;

import com.evacs.control.emergency.domain.EmergencyRoute;
import com.evacs.control.emergency.repository.jpa.EmergencyRouteJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 긴급상황에 연결된 긴급차량 이동 경로를 영속화하고 조회한다. */
@Repository
public class EmergencyRouteRepository {

	private final EmergencyRouteJpaRepository jpa;

	public EmergencyRouteRepository(EmergencyRouteJpaRepository jpa) {
		this.jpa = jpa;
	}

	/** 긴급상황에 등록된 경로 정보 조회(sequence 오름차순). */
	public List<EmergencyRoute> findByEmergencyId(UUID emergencyId) {
		return jpa.findByEmergencyIdOrderBySequenceAsc(emergencyId);
	}

	/** 긴급차량 경로 데이터 저장. */
	public EmergencyRoute save(EmergencyRoute route) {
		return jpa.save(route);
	}

	/** 생성 시 경로 전체를 한 번에 저장한다(지점마다 INSERT 왕복하지 않는다). */
	public List<EmergencyRoute> saveAll(List<EmergencyRoute> routes) {
		return jpa.saveAll(routes);
	}
}
