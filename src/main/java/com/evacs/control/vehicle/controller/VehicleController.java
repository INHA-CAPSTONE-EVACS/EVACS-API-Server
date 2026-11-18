package com.evacs.control.vehicle.controller;

import com.evacs.control.vehicle.dto.DensityQueryDto;
import com.evacs.control.vehicle.dto.DensityResponse;
import com.evacs.control.vehicle.dto.VehicleListResponse;
import com.evacs.control.vehicle.dto.VehicleQueryDto;
import com.evacs.control.vehicle.dto.VehicleResponse;
import com.evacs.control.vehicle.dto.VehicleSummaryResponse;
import com.evacs.control.vehicle.service.VehicleQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관제 대시보드의 차량 조회 HTTP 요청을 처리한다.
 *
 * <p>실시간 차량 정보는 대시보드에 계속 반영되어야 하므로 WebSocket이 아니라 REST Polling이다.
 * 밀어 주는 쪽(ws-server)은 차량용이고, 관제 화면은 "지금 상태"를 주기적으로 당겨 간다.
 */
@RestController
@RequestMapping("/control/vehicles")
public class VehicleController {

	private final VehicleQueryService vehicleQueryService;

	public VehicleController(VehicleQueryService vehicleQueryService) {
		this.vehicleQueryService = vehicleQueryService;
	}

	/** 조회 조건을 검증하고 활성 차량 목록 반환. */
	@GetMapping
	public ResponseEntity<VehicleListResponse> getVehicles(@Valid VehicleQueryDto query) {
		return ResponseEntity.ok(vehicleQueryService.findVehicles(query));
	}

	/** 전체 차량 수 및 차량 유형별 통계 반환. 경로 충돌을 피하려 {vehicleId}보다 먼저 선언한다. */
	@GetMapping("/summary")
	public ResponseEntity<VehicleSummaryResponse> getSummary() {
		return ResponseEntity.ok(vehicleQueryService.getSummary());
	}

	/** 지도 영역과 H3를 기준으로 차량 밀도 조회. */
	@GetMapping("/density")
	public ResponseEntity<DensityResponse> getDensity(@Valid DensityQueryDto query) {
		return ResponseEntity.ok(vehicleQueryService.findDensity(query));
	}

	/** 특정 차량의 현재 상태 조회. */
	@GetMapping("/{vehicleId}")
	public ResponseEntity<VehicleResponse> getVehicle(@PathVariable String vehicleId) {
		return ResponseEntity.ok(vehicleQueryService.findVehicle(vehicleId));
	}
}
