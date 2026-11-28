package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.DispatchRoute;
import java.time.Instant;
import java.util.List;

/**
 * Emergency Worker가 바꾼 최신 경로.
 *
 * <p>생성 요청에 담겨 온 경로({@code /route})와 다른 자원이다. 이쪽이 "실제로 바뀐" 경로다.
 *
 * @param available          false면 워커가 아직 이 출동의 경로를 저장하지 않았다는 뜻이다.
 *                           긴급상황 자체가 없는 것과 구분하려고 404가 아니라 이 플래그로 알린다(cone과 같은 관례).
 * @param coordinatesParsed  false면 저장된 좌표 JSON이 기대 형태가 아니어서 비웠다는 뜻이다.
 */
public record DispatchRouteResponse(
		String dispatchId,
		boolean available,
		String routeId,
		Integer routeVersion,
		List<Coordinate> coordinates,
		boolean coordinatesParsed,
		List<String> roadSegments,
		Integer estimatedDuration,
		String changeReason,
		Instant createdAt) {

	public record Coordinate(double latitude, double longitude) {
	}

	public static DispatchRouteResponse empty(String dispatchId) {
		return new DispatchRouteResponse(
				dispatchId, false, null, null, List.of(), true, List.of(), null, null, null);
	}

	public static DispatchRouteResponse from(DispatchRoute route) {
		List<Coordinate> coordinates = route.coordinates().stream()
				.map(c -> new Coordinate(c.latitude(), c.longitude()))
				.toList();
		return new DispatchRouteResponse(
				route.dispatchId(),
				true,
				route.routeId(),
				route.routeVersion(),
				coordinates,
				route.coordinatesParsed(),
				route.roadSegments(),
				route.estimatedDuration(),
				route.changeReason(),
				route.createdAt());
	}
}
