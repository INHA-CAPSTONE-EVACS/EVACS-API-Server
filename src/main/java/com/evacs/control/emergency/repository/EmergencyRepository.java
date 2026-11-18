package com.evacs.control.emergency.repository;

import com.evacs.control.emergency.domain.Emergency;
import com.evacs.control.emergency.domain.EmergencyStatus;
import com.evacs.control.emergency.dto.EmergencyQueryDto;
import com.evacs.control.emergency.repository.jpa.EmergencyJpaRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * MySQL에 저장되는 긴급상황의 영속 데이터를 관리한다.
 *
 * <p>설계서의 메서드 이름을 그대로 쓰는 경계 클래스다. Spring Data 인터페이스를 서비스에서
 * 직접 쓰지 않는 이유는 동적 조건 조립(Specification)을 조회 책임이 있는 이 계층에 가두기 위해서다.
 */
@Repository
public class EmergencyRepository {

	private final EmergencyJpaRepository jpa;

	public EmergencyRepository(EmergencyJpaRepository jpa) {
		this.jpa = jpa;
	}

	/** 조건에 맞는 긴급상황 목록 조회. 최근 발생 순. */
	public List<Emergency> findAll(EmergencyQueryDto query) {
		EmergencyStatus status = query.statusOrNull();
		Specification<Emergency> spec = (root, cq, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (query.vehicleId() != null && !query.vehicleId().isBlank()) {
				predicates.add(cb.equal(root.get("vehicleId"), query.vehicleId()));
			}
			if (query.from() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), query.from()));
			}
			if (query.to() != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), query.to()));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
		PageRequest page = PageRequest.of(
				0, query.limitOrDefault(), Sort.by(Sort.Direction.DESC, "startedAt"));
		return jpa.findAll(spec, page).getContent();
	}

	/** 긴급상황 ID로 단건 조회. 없으면 null. */
	public Emergency findById(UUID id) {
		return jpa.findById(id).orElse(null);
	}

	/**
	 * 해당 차량에 진행 중인 긴급상황이 존재하는지 조회.
	 *
	 * <p>이 검사만으로는 동시 생성 경합을 막지 못한다. 최종 방어는
	 * {@code emergency_one_active_per_vehicle} 부분 유니크 인덱스다.
	 */
	public Emergency findActiveByVehicleId(String vehicleId) {
		return jpa.findFirstByVehicleIdAndStatus(vehicleId, EmergencyStatus.ACTIVE).orElse(null);
	}

	/** 새로운 긴급상황 영속화. */
	public Emergency save(Emergency emergency) {
		return jpa.save(emergency);
	}

	/** 긴급상황 상태 변경. */
	public Emergency updateStatus(UUID id, EmergencyStatus status) {
		Emergency emergency = findById(id);
		if (emergency == null) {
			return null;
		}
		emergency.setStatus(status);
		return jpa.save(emergency);
	}
}
