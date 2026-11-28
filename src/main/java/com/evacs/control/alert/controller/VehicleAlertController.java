package com.evacs.control.alert.controller;

import com.evacs.control.alert.dto.VehicleAlertListResponse;
import com.evacs.control.alert.dto.VehicleAlertStatusResponse;
import com.evacs.control.alert.service.VehicleAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 차량별 알림 현황 조회.
 *
 * <p>차량 조회와 같은 {@code /control/vehicles} 아래에 둔다 — 대시보드는 차량 목록과 이 응답을
 * vehicleId로 맞춰 쓰기 때문이다. 리터럴 경로 {@code /alerts}는 {@code /{vehicleId}}보다
 * 구체적이라 {@link com.evacs.control.vehicle.controller.VehicleController}의 단건 조회와
 * 충돌하지 않는다.
 */
@RestController
@RequestMapping("/control/vehicles")
public class VehicleAlertController {

	private final VehicleAlertService vehicleAlertService;

	public VehicleAlertController(VehicleAlertService vehicleAlertService) {
		this.vehicleAlertService = vehicleAlertService;
	}

	/** 지금 알림이 떠 있는 차량 전체. */
	@GetMapping("/alerts")
	public ResponseEntity<VehicleAlertListResponse> getAlerts() {
		return ResponseEntity.ok(
				VehicleAlertListResponse.of(
						vehicleAlertService.findAlerts(), vehicleAlertService.lastPolledAt()));
	}

	/** 특정 차량에 알림이 왔는지. */
	@GetMapping("/{vehicleId}/alert")
	public ResponseEntity<VehicleAlertStatusResponse> getAlert(@PathVariable String vehicleId) {
		return ResponseEntity.ok(
				VehicleAlertStatusResponse.of(
						vehicleId,
						vehicleAlertService.findAlert(vehicleId),
						vehicleAlertService.lastPolledAt()));
	}
}
