package com.evacs.control.emergency.domain;

import java.time.Instant;
import java.util.List;

/**
 * Emergency Worker가 {@code dispatch_route}에 쌓은 경로 1건.
 *
 * <p>JPA 엔티티가 아니다. 이 테이블의 소유자는 워커이고 관제 API는 읽기만 한다
 * (V3__worker_tables.sql 주석 참조 — {@code ddl-auto: validate}는 매핑된 테이블만 본다).
 * 그래서 네이티브 조회 결과를 담는 읽기 전용 레코드로 둔다.
 *
 * @param coordinatesParsed false면 {@code route_coordinates}가 기대한 형태
 *                          ({@code [{latitude,longitude}, ...]})가 아니어서 좌표를 비웠다는 뜻이다.
 *                          원문 JSON을 그대로 돌려주면 대시보드가 쓸 수 없으므로 플래그로 알린다.
 */
public record DispatchRoute(
		String routeId,
		String dispatchId,
		int routeVersion,
		List<Coordinate> coordinates,
		boolean coordinatesParsed,
		List<String> roadSegments,
		int estimatedDuration,
		String changeReason,
		Instant createdAt) {

	/** 워커가 쓰는 좌표 형태. ROUTE_UPDATE_REQUESTED의 waypoints와 같은 {latitude, longitude}다. */
	public record Coordinate(double latitude, double longitude) {
	}

	/**
	 * 경로가 몇 번 바뀌었는지 보기 위한 버전 요약. 좌표 전체는 담지 않고 개수만 센다.
	 */
	public record VersionSummary(
			int routeVersion,
			int coordinateCount,
			int estimatedDuration,
			String changeReason,
			Instant createdAt) {
	}
}
