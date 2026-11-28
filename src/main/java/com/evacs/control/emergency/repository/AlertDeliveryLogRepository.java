package com.evacs.control.emergency.repository;

import com.evacs.control.emergency.domain.AlertDeliveryLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Alert Worker가 남긴 양보 알림 전송 이력({@code alert_delivery_log})을 조회한다.
 *
 * <p>워커 소유 테이블이라 JPA 매핑 없이 네이티브 조회만 쓰고, SQL을 이 클래스 안에 가둔다
 * ({@link DispatchRouteRepository}와 같은 규칙). {@code dispatch_id}에 FK는 없고 값은
 * {@code emergency.id}의 문자열이다.
 */
@Repository
public class AlertDeliveryLogRepository {

	/**
	 * 최근 발송이 위로 오게 정렬한다. {@code PENDING}은 아직 발송 시각이 없어(NULL) MySQL의
	 * DESC 정렬에서 뒤로 밀리므로, 같은 알림의 버전 순서를 보조 키로 둔다.
	 */
	private static final String FIND_BY_DISPATCH = """
			SELECT alert_log_id, alert_id, alert_version, target_vehicle_id, selection_reason,
			       estimated_arrival_seconds, alert_status, first_sent_at, acknowledged_at,
			       cleared_at, expires_at, clear_reason
			  FROM alert_delivery_log
			 WHERE dispatch_id = ?
			 ORDER BY first_sent_at DESC, alert_id ASC, alert_version DESC
			 LIMIT ?
			""";

	private final JdbcTemplate jdbc;

	public AlertDeliveryLogRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 해당 출동의 알림 전송 이력 조회(최근 발송 우선). */
	public List<AlertDeliveryLog> findByDispatchId(String dispatchId, int limit) {
		return jdbc.query(
				FIND_BY_DISPATCH,
				(rs, rowNum) -> new AlertDeliveryLog(
						rs.getString("alert_log_id"),
						rs.getString("alert_id"),
						rs.getInt("alert_version"),
						rs.getString("target_vehicle_id"),
						rs.getString("selection_reason"),
						// NULL을 0으로 뭉개지 않도록 getInt 대신 박싱 타입으로 읽는다.
						rs.getObject("estimated_arrival_seconds", Integer.class),
						rs.getString("alert_status"),
						readInstant(rs, "first_sent_at"),
						readInstant(rs, "acknowledged_at"),
						readInstant(rs, "cleared_at"),
						readInstant(rs, "expires_at"),
						rs.getString("clear_reason")),
				dispatchId,
				limit);
	}

	/** 워커 테이블의 시각은 DATETIME(3) UTC 저장이다. 세션 타임존에 흔들리지 않게 UTC로 못 박는다. */
	private static Instant readInstant(ResultSet rs, String column) throws SQLException {
		LocalDateTime value = rs.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}
}
