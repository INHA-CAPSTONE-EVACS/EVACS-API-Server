package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.DispatchRoute;
import java.time.Instant;
import java.util.List;

/**
 * Emergency Worker가 바꾼 경로의 버전 이력 요약. 버전 오름차순이다.
 *
 * <p>"경로가 몇 번 바뀌었는지"를 보는 용도라 좌표 전체는 넣지 않고 개수만 담는다
 * (전체 좌표는 최신 1건 조회로 본다).
 */
public record DispatchRouteHistoryResponse(String dispatchId, int count, List<Entry> versions) {

	public record Entry(
			int routeVersion,
			int coordinateCount,
			int estimatedDuration,
			String changeReason,
			Instant createdAt) {

		public static Entry from(DispatchRoute.VersionSummary s) {
			return new Entry(
					s.routeVersion(), s.coordinateCount(), s.estimatedDuration(), s.changeReason(), s.createdAt());
		}
	}

	public static DispatchRouteHistoryResponse of(String dispatchId, List<DispatchRoute.VersionSummary> summaries) {
		List<Entry> versions = summaries.stream().map(Entry::from).toList();
		return new DispatchRouteHistoryResponse(dispatchId, versions.size(), versions);
	}
}
