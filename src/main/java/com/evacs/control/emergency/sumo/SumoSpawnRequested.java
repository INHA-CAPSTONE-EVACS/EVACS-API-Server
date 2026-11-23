package com.evacs.control.emergency.sumo;

import java.util.UUID;

/**
 * SUMO 시뮬레이터에 긴급차량 1대를 투입해 달라는 요청.
 *
 * <p>커밋 후에 처리해야 하므로(시뮬레이터 호출은 되돌릴 수 없다) 트랜잭션 안에서 발행하고
 * {@link SumoVehicleSpawner}가 AFTER_COMMIT에 받는다.
 */
public record SumoSpawnRequested(
		UUID emergencyId,
		String vehicleId,
		String depotId,
		double destinationLat,
		double destinationLng) {
}
