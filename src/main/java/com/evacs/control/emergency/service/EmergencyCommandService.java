package com.evacs.control.emergency.service;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.common.error.ApiException;
import com.evacs.control.common.geo.H3Support;
import com.evacs.control.emergency.domain.Emergency;
import com.evacs.control.emergency.domain.EmergencyRoute;
import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.repository.EmergencyRepository;
import com.evacs.control.emergency.repository.EmergencyRouteRepository;
import com.evacs.control.emergency.sumo.SumoSpawnRequested;
import com.evacs.control.outbox.domain.EmergencyEventType;
import com.evacs.control.outbox.domain.OutboxEvent;
import com.evacs.control.outbox.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 긴급상황의 생성·종료 명령을 처리하고, 해당 상태 변경에 대한 Outbox 이벤트를 저장한다.
 *
 * <p>핵심은 <b>상태 변경과 이벤트 저장이 한 트랜잭션</b>이라는 점이다. 여기서 브로커로 직접
 * 발행하면 "DB는 커밋됐는데 발행은 실패" 또는 "발행은 됐는데 DB가 롤백"이 생긴다.
 * 실제 발행은 커밋된 Outbox 행을 보고 OutboxProcessor가 비동기로 한다.
 */
@Service
public class EmergencyCommandService {

	private static final Logger log = LoggerFactory.getLogger(EmergencyCommandService.class);

	private final EmergencyRepository emergencyRepository;
	private final EmergencyRouteRepository routeRepository;
	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final H3Support h3;
	private final EvacsProperties properties;
	private final ApplicationEventPublisher events;

	public EmergencyCommandService(
			EmergencyRepository emergencyRepository,
			EmergencyRouteRepository routeRepository,
			OutboxRepository outboxRepository,
			ObjectMapper evacsObjectMapper,
			H3Support h3,
			EvacsProperties properties,
			ApplicationEventPublisher events) {
		this.emergencyRepository = emergencyRepository;
		this.routeRepository = routeRepository;
		this.outboxRepository = outboxRepository;
		this.objectMapper = evacsObjectMapper;
		this.h3 = h3;
		this.properties = properties;
		this.events = events;
	}

	/**
	 * 긴급상황 생성 및 발생 이벤트 저장.
	 *
	 * <p>같은 차량에 진행 중인 긴급상황이 있으면 409다. 이 검사와 INSERT 사이의 경합은
	 * 부분 유니크 인덱스가 막고, 그 제약 위반은 전역 핸들러가 409로 바꾼다.
	 */
	@Transactional
	public Emergency createEmergency(CreateEmergencyDto dto) {
		if (dto.sumoMode()) {
			validateSumoMode(dto);
		}

		Emergency active = emergencyRepository.findActiveByVehicleId(dto.vehicleId());
		if (active != null) {
			throw ApiException.conflict(
					"이미 진행 중인 긴급상황이 있습니다: vehicleId=%s emergencyId=%s"
							.formatted(dto.vehicleId(), active.getId()));
		}

		Emergency emergency = emergencyRepository.save(Emergency.start(
				dto.vehicleId(),
				dto.emergencyType(),
				dto.originLat(),
				dto.originLng(),
				dto.destinationLat(),
				dto.destinationLng(),
				dto.description(),
				dto.sumoMode()));

		List<EmergencyRoute> routes = toRoutes(emergency.getId(), dto.routeOrEmpty());
		if (!routes.isEmpty()) {
			routeRepository.saveAll(routes);
		}

		outboxRepository.save(OutboxEvent.emergencyEvent(
				emergency.getId().toString(),
				EmergencyEventType.EMERGENCY_CREATED,
				createdPayload(emergency, routes)));

		if (dto.sumoMode()) {
			// 시뮬레이터 호출은 커밋 후다(롤백해도 SUMO의 차량은 되돌릴 수 없다).
			events.publishEvent(new SumoSpawnRequested(
					emergency.getId(),
					emergency.getVehicleId(),
					resolveDepotId(dto),
					dto.destinationLat(),
					dto.destinationLng()));
		}

		log.info("긴급상황 생성: id={} vehicleId={} routePoints={} sumo={}",
				emergency.getId(), emergency.getVehicleId(), routes.size(), emergency.isSumo());
		return emergency;
	}

	/**
	 * SUMO 모드 요청의 사전 검사.
	 *
	 * <p>목적지가 없으면 시뮬레이터가 경로를 만들 수 없으므로 생성 자체를 막는다 — 커밋 후에
	 * 알게 되면 투입 없는 긴급상황만 남는다.
	 */
	private void validateSumoMode(CreateEmergencyDto dto) {
		if (!properties.sumo().enabled()) {
			throw ApiException.badRequest("SUMO 모드가 비활성화되어 있습니다(evacs.sumo.enabled=false).");
		}
		if (dto.destinationLat() == null || dto.destinationLng() == null) {
			throw ApiException.badRequest("SUMO 모드에서는 destinationLat, destinationLng가 필요합니다.");
		}
	}

	private String resolveDepotId(CreateEmergencyDto dto) {
		String depotId = dto.sumoDepotId();
		return depotId == null || depotId.isBlank() ? properties.sumo().defaultDepotId() : depotId;
	}

	/** 긴급상황 종료 상태 전이 및 종료 이벤트 저장. */
	@Transactional
	public Emergency endEmergency(String id) {
		UUID emergencyId = EmergencyQueryService.parseId(id);
		Emergency emergency = emergencyRepository.findById(emergencyId);
		if (emergency == null) {
			throw ApiException.notFound("긴급상황", id);
		}
		if (!emergency.isActive()) {
			throw ApiException.conflict("이미 종료된 긴급상황입니다: " + id);
		}

		emergency.end(Instant.now());
		emergencyRepository.save(emergency);

		outboxRepository.save(OutboxEvent.emergencyEvent(
				emergency.getId().toString(), EmergencyEventType.EMERGENCY_ENDED, endedPayload(emergency)));

		log.info("긴급상황 종료: id={} vehicleId={}", emergency.getId(), emergency.getVehicleId());
		return emergency;
	}

	private List<EmergencyRoute> toRoutes(UUID emergencyId, List<CreateEmergencyDto.RoutePoint> points) {
		int resolution = properties.vehicle().defaultDensityResolution();
		List<EmergencyRoute> routes = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			CreateEmergencyDto.RoutePoint p = points.get(i);
			int sequence = p.sequence() != null ? p.sequence() : i;
			// 경로 지점의 H3 셀은 생성 시점에 한 번 계산해 둔다. 경로는 움직이지 않으므로
			// 매 조회마다 다시 계산할 이유가 없다(차량 위치와 다른 점).
			String cell = h3.cellOf(p.latitude(), p.longitude(), resolution);
			routes.add(new EmergencyRoute(
					emergencyId, sequence, p.latitude(), p.longitude(), cell, p.etaSeconds()));
		}
		return routes;
	}

	private String createdPayload(Emergency e, List<EmergencyRoute> routes) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("emergencyId", e.getId().toString());
		node.put("vehicleId", e.getVehicleId());
		node.put("emergencyType", e.getEmergencyType());
		node.put("status", e.getStatus().name());
		node.put("isSumo", e.isSumo());
		node.put("startedAt", e.getStartedAt().toString());
		putCoordinate(node, "origin", e.getOriginLat(), e.getOriginLng());
		putCoordinate(node, "destination", e.getDestinationLat(), e.getDestinationLng());
		var routeArray = node.putArray("route");
		for (EmergencyRoute r : routes) {
			ObjectNode point = routeArray.addObject();
			point.put("sequence", r.getSequence());
			point.put("latitude", r.getLatitude());
			point.put("longitude", r.getLongitude());
			point.put("h3Index", r.getH3Index());
			if (r.getEtaSeconds() != null) {
				point.put("etaSeconds", r.getEtaSeconds());
			}
		}
		return node.toString();
	}

	private String endedPayload(Emergency e) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("emergencyId", e.getId().toString());
		node.put("vehicleId", e.getVehicleId());
		node.put("status", e.getStatus().name());
		node.put("endedAt", e.getEndedAt() == null ? null : e.getEndedAt().toString());
		return node.toString();
	}

	private void putCoordinate(ObjectNode node, String name, Double lat, Double lng) {
		if (lat == null || lng == null) {
			return;
		}
		ObjectNode child = node.putObject(name);
		child.put("latitude", lat);
		child.put("longitude", lng);
	}
}
