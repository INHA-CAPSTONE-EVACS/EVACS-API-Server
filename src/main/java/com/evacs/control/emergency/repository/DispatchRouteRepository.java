package com.evacs.control.emergency.repository;

import com.evacs.control.emergency.domain.DispatchRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Emergency Worker가 바꾼 경로 이력({@code dispatch_route})을 조회한다.
 *
 * <p>이 테이블은 워커 소유라 JPA로 매핑하지 않는다(V3__worker_tables.sql). 그래서 엔티티 없이
 * {@link JdbcTemplate} 네이티브 조회를 쓰고, 그 SQL과 JSON 컬럼 해석을 모두 이 클래스 안에 가둔다.
 * {@code dispatch_id}에는 FK가 없고 실제 값은 {@code emergency.id}의 문자열이다.
 */
@Repository
public class DispatchRouteRepository {

	private static final Logger log = LoggerFactory.getLogger(DispatchRouteRepository.class);

	/** 최신 1건. UNIQUE (dispatch_id, route_version)이 있어 버전 역순 1행이 곧 최신이다. */
	private static final String FIND_LATEST = """
			SELECT route_id, dispatch_id, route_version, route_coordinates, road_segments,
			       estimated_duration, change_reason, created_at
			  FROM dispatch_route
			 WHERE dispatch_id = ?
			 ORDER BY route_version DESC
			 LIMIT 1
			""";

	/**
	 * 버전 요약 목록. 좌표 전체를 끌고 오지 않으려고 개수만 DB에서 센다.
	 * 배열이 아닌 JSON에 JSON_LENGTH를 쓰면 키 개수가 나와 버리므로, 배열이 아니면 0으로 둔다
	 * (좌표 파싱 실패를 빈 배열로 처리하는 것과 같은 규칙).
	 */
	private static final String FIND_SUMMARIES = """
			SELECT route_version,
			       CASE WHEN JSON_TYPE(route_coordinates) = 'ARRAY'
			            THEN JSON_LENGTH(route_coordinates) ELSE 0 END AS coordinate_count,
			       estimated_duration, change_reason, created_at
			  FROM dispatch_route
			 WHERE dispatch_id = ?
			 ORDER BY route_version ASC
			""";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public DispatchRouteRepository(JdbcTemplate jdbc, ObjectMapper evacsObjectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = evacsObjectMapper;
	}

	/** 해당 출동의 최신 버전 경로 1건 조회. 워커가 아직 아무것도 쌓지 않았으면 null. */
	public DispatchRoute findLatestByDispatchId(String dispatchId) {
		List<DispatchRoute> found = jdbc.query(FIND_LATEST, routeMapper(), dispatchId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** 해당 출동의 경로 버전 요약 목록 조회(route_version 오름차순). */
	public List<DispatchRoute.VersionSummary> findVersionSummaries(String dispatchId) {
		return jdbc.query(
				FIND_SUMMARIES,
				(rs, rowNum) -> new DispatchRoute.VersionSummary(
						rs.getInt("route_version"),
						rs.getInt("coordinate_count"),
						rs.getInt("estimated_duration"),
						rs.getString("change_reason"),
						readCreatedAt(rs)),
				dispatchId);
	}

	private RowMapper<DispatchRoute> routeMapper() {
		return (rs, rowNum) -> {
			String dispatchId = rs.getString("dispatch_id");
			String rawCoordinates = rs.getString("route_coordinates");
			// 파싱 실패는 null로 돌아온다(빈 배열 = 정상 파싱 결과와 구분해야 한다).
			List<DispatchRoute.Coordinate> coordinates = parseCoordinates(dispatchId, rawCoordinates);
			return new DispatchRoute(
					rs.getString("route_id"),
					dispatchId,
					rs.getInt("route_version"),
					coordinates == null ? List.of() : coordinates,
					coordinates != null,
					parseRoadSegments(dispatchId, rs.getString("road_segments")),
					rs.getInt("estimated_duration"),
					rs.getString("change_reason"),
					readCreatedAt(rs));
		};
	}

	/**
	 * 좌표 JSON을 구조화한다. 기대 형태는 워커의 경로 변경 이벤트와 같은
	 * {@code [{"latitude":37.4,"longitude":126.6}, ...]}다.
	 *
	 * <p>형태가 다르면 원문 문자열을 응답에 흘리지 않고 null을 돌려(= 빈 배열 + 경고 플래그) 로그만 남긴다.
	 * 대시보드가 좌표 배열을 기대하는데 문자열이 섞이면 거기서 터지기 때문이다.
	 */
	private List<DispatchRoute.Coordinate> parseCoordinates(String dispatchId, String raw) {
		try {
			JsonNode root = objectMapper.readTree(raw);
			if (!root.isArray()) {
				throw new IllegalArgumentException("배열이 아님: " + root.getNodeType());
			}
			List<DispatchRoute.Coordinate> coordinates = new ArrayList<>(root.size());
			for (JsonNode node : root) {
				JsonNode lat = node.path("latitude");
				JsonNode lng = node.path("longitude");
				if (!lat.isNumber() || !lng.isNumber()) {
					throw new IllegalArgumentException("latitude/longitude 숫자가 아님: " + node);
				}
				coordinates.add(new DispatchRoute.Coordinate(lat.asDouble(), lng.asDouble()));
			}
			return coordinates;
		} catch (Exception e) {
			log.warn("dispatch_route.route_coordinates 파싱 실패: dispatchId={} error={}", dispatchId, e.getMessage());
			return null;
		}
	}

	/**
	 * 도로 구간 JSON. 워커는 edge id 문자열 배열로 쓴다(congestedSegments).
	 * 객체가 들어오면 식별에 쓸 수 있도록 JSON 원문 한 줄로 평탄화하고, 배열이 아니면 비워 둔다.
	 */
	private List<String> parseRoadSegments(String dispatchId, String raw) {
		try {
			JsonNode root = objectMapper.readTree(raw);
			if (!root.isArray()) {
				throw new IllegalArgumentException("배열이 아님: " + root.getNodeType());
			}
			List<String> segments = new ArrayList<>(root.size());
			for (JsonNode node : root) {
				segments.add(node.isTextual() ? node.asText() : node.toString());
			}
			return segments;
		} catch (Exception e) {
			log.warn("dispatch_route.road_segments 파싱 실패: dispatchId={} error={}", dispatchId, e.getMessage());
			return List.of();
		}
	}

	/**
	 * {@code created_at}은 DATETIME(3)에 UTC로 저장된다(워커가 ms 정밀도로 기록).
	 * 세션 타임존에 따라 흔들리지 않도록 LocalDateTime으로 읽고 UTC로 못 박는다.
	 */
	private static java.time.Instant readCreatedAt(ResultSet rs) throws SQLException {
		LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
		return createdAt == null ? null : createdAt.toInstant(ZoneOffset.UTC);
	}
}
