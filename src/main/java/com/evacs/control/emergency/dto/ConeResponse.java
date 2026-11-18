package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.ConeSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * 긴급차량의 현재 영향 H3 셀.
 *
 * @param available false면 Worker가 아직 계산하지 않았거나 Redis에서 만료된 것이다.
 *                  긴급상황 자체가 없는 것과 구분하려고 404가 아니라 이 플래그로 알린다.
 */
public record ConeResponse(
		String emergencyId,
		boolean available,
		String vehicleId,
		long version,
		Integer resolution,
		List<Cell> cells,
		Instant updatedAt) {

	public record Cell(String h3Index, double centerLat, double centerLng) {
	}

	public static ConeResponse empty(String emergencyId) {
		return new ConeResponse(emergencyId, false, null, 0, null, List.of(), null);
	}

	public static ConeResponse of(ConeSnapshot snapshot, List<Cell> cells) {
		return new ConeResponse(
				snapshot.emergencyId(),
				true,
				snapshot.vehicleId(),
				snapshot.version(),
				snapshot.resolution(),
				cells,
				snapshot.updatedAt());
	}
}
