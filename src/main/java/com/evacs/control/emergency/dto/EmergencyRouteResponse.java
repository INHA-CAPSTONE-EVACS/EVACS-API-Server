package com.evacs.control.emergency.dto;

import com.evacs.control.emergency.domain.EmergencyRoute;
import java.util.List;

/** 긴급차량의 경로 응답. 지점은 sequence 오름차순이다. */
public record EmergencyRouteResponse(String emergencyId, int pointCount, List<Point> points) {

	public record Point(int sequence, double latitude, double longitude, String h3Index, Integer etaSeconds) {

		public static Point from(EmergencyRoute r) {
			return new Point(r.getSequence(), r.getLatitude(), r.getLongitude(), r.getH3Index(), r.getEtaSeconds());
		}
	}

	public static EmergencyRouteResponse of(String emergencyId, List<EmergencyRoute> routes) {
		List<Point> points = routes.stream().map(Point::from).toList();
		return new EmergencyRouteResponse(emergencyId, points.size(), points);
	}
}
