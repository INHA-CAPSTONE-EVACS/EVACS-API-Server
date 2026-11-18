package com.evacs.control.emergency.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * 긴급차량의 moving cone — 지금 영향을 주는 H3 셀 집합.
 *
 * <p>Emergency Worker가 계산해 Redis에 JSON으로 써 두고 API 서버는 읽기만 한다.
 * 긴급차량이 움직일 때마다 바뀌므로 MySQL이 아니라 Redis에 둔다.
 *
 * @param version   Worker가 갱신할 때마다 올리는 번호. 대시보드가 역행 갱신을 거를 때 쓴다.
 * @param cells     영향 셀의 H3 인덱스 목록
 * @param updatedAt Worker가 마지막으로 계산한 시각
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConeSnapshot(
		String emergencyId,
		String vehicleId,
		long version,
		Integer resolution,
		List<String> cells,
		Instant updatedAt) {
}
