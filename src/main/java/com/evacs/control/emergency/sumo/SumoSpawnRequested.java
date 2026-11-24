package com.evacs.control.emergency.sumo;

import java.util.UUID;

/**
 * SUMO 시뮬레이터에 긴급차량 1대를 투입해 달라는 요청.
 *
 * <p>커밋 후에 처리해야 하므로(시뮬레이터 호출은 되돌릴 수 없다) 트랜잭션 안에서 발행하고
 * {@link SumoVehicleSpawner}가 AFTER_COMMIT에 받는다.
 *
 * @param originLat 출발 좌표. null이면 시뮬레이터가 {@code depotId} 차고지에서 출발시킨다.
 */
public record SumoSpawnRequested(
		UUID emergencyId,
		String vehicleId,
		String depotId,
		Double originLat,
		Double originLng,
		double destinationLat,
		double destinationLng) {

	/** 출발 좌표를 명시했는지. 둘 중 하나만 있으면 차고지 출발로 본다. */
	public boolean hasOrigin() {
		return originLat != null && originLng != null;
	}
}
