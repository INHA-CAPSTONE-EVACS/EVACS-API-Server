package com.evacs.control.emergency.service;

import com.evacs.control.common.error.ApiException;
import com.evacs.control.common.geo.H3Support;
import com.evacs.control.emergency.domain.ConeSnapshot;
import com.evacs.control.emergency.domain.DispatchRoute;
import com.evacs.control.emergency.domain.Emergency;
import com.evacs.control.emergency.dto.AlertedVehiclesResponse;
import com.evacs.control.emergency.dto.ConeResponse;
import com.evacs.control.emergency.dto.DispatchRouteHistoryResponse;
import com.evacs.control.emergency.dto.DispatchRouteResponse;
import com.evacs.control.emergency.dto.EmergencyListResponse;
import com.evacs.control.emergency.dto.EmergencyQueryDto;
import com.evacs.control.emergency.dto.EmergencyResponse;
import com.evacs.control.emergency.dto.EmergencyRouteResponse;
import com.evacs.control.emergency.dto.EventLogResponse;
import com.evacs.control.emergency.repository.DispatchRouteRepository;
import com.evacs.control.emergency.repository.EmergencyRepository;
import com.evacs.control.emergency.repository.EmergencyRouteRepository;
import com.evacs.control.emergency.repository.EventLogRepository;
import com.evacs.control.emergency.repository.RedisAlertRepository;
import com.evacs.control.emergency.repository.RedisConeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 긴급상황과 관련된 영속 데이터를 조회하고 대시보드 응답으로 가공한다.
 *
 * <p>영속 데이터는 MySQL에서, Worker가 계산하는 휘발성 상태(cone/알림 수신)는 Redis에서 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class EmergencyQueryService {

	private final EmergencyRepository emergencyRepository;
	private final EmergencyRouteRepository routeRepository;
	private final DispatchRouteRepository dispatchRouteRepository;
	private final EventLogRepository eventLogRepository;
	private final RedisConeRepository coneRepository;
	private final RedisAlertRepository alertRepository;
	private final H3Support h3;

	public EmergencyQueryService(
			EmergencyRepository emergencyRepository,
			EmergencyRouteRepository routeRepository,
			DispatchRouteRepository dispatchRouteRepository,
			EventLogRepository eventLogRepository,
			RedisConeRepository coneRepository,
			RedisAlertRepository alertRepository,
			H3Support h3) {
		this.emergencyRepository = emergencyRepository;
		this.routeRepository = routeRepository;
		this.dispatchRouteRepository = dispatchRouteRepository;
		this.eventLogRepository = eventLogRepository;
		this.coneRepository = coneRepository;
		this.alertRepository = alertRepository;
		this.h3 = h3;
	}

	/** 긴급상황 목록 조회 및 필터링. */
	public EmergencyListResponse findEmergencies(EmergencyQueryDto query) {
		List<Emergency> found;
		try {
			found = emergencyRepository.findAll(query);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		return EmergencyListResponse.of(found.stream().map(EmergencyResponse::from).toList());
	}

	/** 긴급상황 상세 정보 조회. */
	public EmergencyResponse findEmergency(String id) {
		return EmergencyResponse.from(require(id));
	}

	/** 긴급차량의 경로 데이터 조회. */
	public EmergencyRouteResponse findRoute(String id) {
		Emergency emergency = require(id);
		return EmergencyRouteResponse.of(id, routeRepository.findByEmergencyId(emergency.getId()));
	}

	/**
	 * Emergency Worker가 실제로 바꾼 최신 경로 조회.
	 *
	 * <p>{@link #findRoute}는 생성 요청에 담겨 온 경로(emergency_route)를 돌려준다. 워커가 운행 중
	 * 다시 계산한 경로는 append-only인 dispatch_route에 쌓이고, 그중 최신 버전이 "지금 가는 길"이다.
	 *
	 * <p>긴급상황은 있는데 경로가 아직 없는 것은 정상 상태(워커가 계산 전)라 404가 아니라
	 * {@code available=false}로 돌린다(cone 조회와 같은 관례).
	 */
	public DispatchRouteResponse findDispatchRoute(String id) {
		Emergency emergency = require(id);
		// dispatch_route.dispatch_id에는 FK가 없고, 값은 emergency.id의 문자열이다.
		String dispatchId = emergency.getId().toString();
		DispatchRoute latest = dispatchRouteRepository.findLatestByDispatchId(dispatchId);
		return latest == null ? DispatchRouteResponse.empty(dispatchId) : DispatchRouteResponse.from(latest);
	}

	/** 워커가 경로를 바꾼 이력(버전 오름차순) 조회. 좌표는 개수만 담는다. */
	public DispatchRouteHistoryResponse findDispatchRouteHistory(String id) {
		Emergency emergency = require(id);
		String dispatchId = emergency.getId().toString();
		return DispatchRouteHistoryResponse.of(
				dispatchId, dispatchRouteRepository.findVersionSummaries(dispatchId));
	}

	/** 특정 긴급상황의 이벤트 이력 조회. */
	public List<EventLogResponse> findEvents(String id) {
		Emergency emergency = require(id);
		return eventLogRepository.findByEmergencyId(emergency.getId()).stream()
				.map(EventLogResponse::from)
				.toList();
	}

	/**
	 * Emergency Worker가 계산한 최신 영향 셀 조회.
	 *
	 * <p>긴급상황은 있는데 cone이 아직 없는 것은 정상 상태(Worker가 계산 전)라
	 * 404가 아니라 {@code available=false}로 돌린다.
	 */
	public ConeResponse findCone(String emergencyId) {
		require(emergencyId);
		ConeSnapshot snapshot = coneRepository.findByEmergencyId(emergencyId);
		if (snapshot == null || snapshot.cells() == null || snapshot.cells().isEmpty()) {
			return ConeResponse.empty(emergencyId);
		}
		List<ConeResponse.Cell> cells = new ArrayList<>(snapshot.cells().size());
		for (String cell : snapshot.cells()) {
			if (!h3.isValidCell(cell)) {
				continue;
			}
			double[] center = h3.centerOf(cell);
			cells.add(new ConeResponse.Cell(cell, center[0], center[1]));
		}
		return ConeResponse.of(snapshot, cells);
	}

	/** 현재 알림 수신이 확인된 차량 Id 목록 조회. */
	public AlertedVehiclesResponse findAlertedVehicles(String emergencyId) {
		require(emergencyId);
		return AlertedVehiclesResponse.of(emergencyId, alertRepository.findRecievedVehicleId(emergencyId));
	}

	private Emergency require(String id) {
		Emergency emergency = emergencyRepository.findById(parseId(id));
		if (emergency == null) {
			throw ApiException.notFound("긴급상황", id);
		}
		return emergency;
	}

	/** 잘못된 형식의 ID는 404가 아니라 400이다 — 존재 여부를 물을 수 있는 입력이 아니다. */
	static UUID parseId(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("긴급상황 ID 형식이 올바르지 않습니다: " + id);
		}
	}
}
