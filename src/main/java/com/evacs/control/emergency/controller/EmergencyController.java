package com.evacs.control.emergency.controller;

import com.evacs.control.emergency.dto.AlertedVehiclesResponse;
import com.evacs.control.emergency.dto.ConeResponse;
import com.evacs.control.emergency.dto.CreateEmergencyDto;
import com.evacs.control.emergency.dto.EmergencyListResponse;
import com.evacs.control.emergency.dto.EmergencyQueryDto;
import com.evacs.control.emergency.dto.EmergencyResponse;
import com.evacs.control.emergency.dto.EmergencyRouteResponse;
import com.evacs.control.emergency.dto.EventLogResponse;
import com.evacs.control.emergency.service.EmergencyCommandService;
import com.evacs.control.emergency.service.EmergencyQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관제 대시보드의 긴급상황 조회 및 상태 변경 HTTP 요청을 처리한다.
 *
 * <p>조회 요청은 {@link EmergencyQueryService}에, 생성·종료 요청은
 * {@link EmergencyCommandService}에 전달한다(CQRS).
 */
@RestController
@RequestMapping("/control/emergencies")
public class EmergencyController {

	private final EmergencyQueryService queryService;
	private final EmergencyCommandService commandService;

	public EmergencyController(EmergencyQueryService queryService, EmergencyCommandService commandService) {
		this.queryService = queryService;
		this.commandService = commandService;
	}

	/** 긴급상황 목록 조회. */
	@GetMapping
	public ResponseEntity<EmergencyListResponse> getEmergencies(@Valid EmergencyQueryDto query) {
		return ResponseEntity.ok(queryService.findEmergencies(query));
	}

	/** 특정 긴급상황 상세 정보 조회. */
	@GetMapping("/{id}")
	public ResponseEntity<EmergencyResponse> getEmergency(@PathVariable String id) {
		return ResponseEntity.ok(queryService.findEmergency(id));
	}

	/** 긴급상황에 등록된 경로 정보 조회. */
	@GetMapping("/{id}/route")
	public ResponseEntity<EmergencyRouteResponse> getRoute(@PathVariable String id) {
		return ResponseEntity.ok(queryService.findRoute(id));
	}

	/** 긴급상황 관련 이벤트 전송 이력 조회. */
	@GetMapping("/{id}/events")
	public ResponseEntity<List<EventLogResponse>> getEvents(@PathVariable String id) {
		return ResponseEntity.ok(queryService.findEvents(id));
	}

	/** 현재 긴급 차량의 영향 H3 셀 조회. */
	@GetMapping("/{id}/cone")
	public ResponseEntity<ConeResponse> getCone(@PathVariable String id) {
		return ResponseEntity.ok(queryService.findCone(id));
	}

	/** 해당 긴급상황의 양보 알림 수신 차량 조회. */
	@GetMapping("/{id}/alerted-vehicles")
	public ResponseEntity<AlertedVehiclesResponse> getAlertedVehicles(@PathVariable String id) {
		return ResponseEntity.ok(queryService.findAlertedVehicles(id));
	}

	/** 새로운 긴급상황 생성 요청. */
	@PostMapping
	public ResponseEntity<EmergencyResponse> createEmergency(@Valid @RequestBody CreateEmergencyDto dto) {
		EmergencyResponse created = EmergencyResponse.from(commandService.createEmergency(dto));
		return ResponseEntity.created(URI.create("/control/emergencies/" + created.id())).body(created);
	}

	/**
	 * 기존 긴급상황 종료 요청.
	 *
	 * <p>DELETE가 아니라 POST인 것은 자원을 지우는 것이 아니라 상태를 전이시키기 때문이다.
	 * 종료된 긴급상황의 이력은 그대로 남아야 한다.
	 */
	@PostMapping("/{id}/end")
	public ResponseEntity<EmergencyResponse> endEmergency(@PathVariable String id) {
		return ResponseEntity.ok(EmergencyResponse.from(commandService.endEmergency(id)));
	}
}
