package com.evacs.control.vehicle.dto;

import java.time.Instant;
import java.util.List;

/**
 * H3 셀 단위 차량 밀도.
 *
 * @param cells 차량이 1대 이상 있는 셀만 담는다(빈 셀은 보내지 않는다).
 */
public record DensityResponse(int resolution, int cellCount, List<Cell> cells, Instant queriedAt) {

	/**
	 * @param h3Index   셀 인덱스(16진 문자열)
	 * @param centerLat 셀 중심 위도. 대시보드가 셀을 그릴 때 쓴다.
	 */
	public record Cell(
			String h3Index,
			double centerLat,
			double centerLng,
			long vehicleCount,
			long normalCount,
			long emergencyCount) {
	}

	public static DensityResponse of(int resolution, List<Cell> cells) {
		return new DensityResponse(resolution, cells.size(), cells, Instant.now());
	}
}
