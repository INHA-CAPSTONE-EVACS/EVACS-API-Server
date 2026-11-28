package com.evacs.control.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code evacs.*} 설정. 환경변수 읽기와 기본값은 이 클래스와 application.yml에만 둔다.
 *
 * <p>Redis 키는 evacs-ws-server / Emergency Worker와 공유하는 계약이므로
 * 값이 어긋나면 조회가 조용히 빈 결과를 돌려준다. 반드시 같은 값을 쓸 것.
 */
@ConfigurationProperties(prefix = "evacs")
public record EvacsProperties(Redis redis, Vehicle vehicle, Alert alert, Outbox outbox, Sumo sumo) {

	/**
	 * @param keyPrefix        Location Worker 키에 붙는 접두사. 워커는 키 이름을 코드에 고정해 두므로
	 *                         운영에서는 빈 문자열이어야 한다(테스트가 같은 Redis를 쓸 때만 격리용으로 쓴다).
	 * @param coneKeyPattern   긴급차량 영향 셀(cone) 키 패턴. {@code %s} 자리에 emergencyId.
	 * @param alertKeyPattern  알림 수신이 확인된 차량 ID 집합 키 패턴. {@code %s} 자리에 emergencyId.
	 * @param emergencyStream  긴급상황 이벤트 발행 스트림. Emergency Worker가 소비한다.
	 */
	public record Redis(
			String keyPrefix,
			String coneKeyPattern,
			String alertKeyPattern,
			String emergencyStream,
			long emergencyStreamMaxlen) {

		/** 비어 있지 않은 H3 셀 집합(워커 소유). */
		public String activeCellsKey() {
			return prefix() + "h3:cells:active";
		}

		/** 셀별 차량 ZSET 키 접두사. 뒤에 h3CellId가 붙는다(워커 소유). */
		public String cellKeyPrefix() {
			return prefix() + "h3:vehicles:";
		}

		/** 차량 상태 Hash 키 접두사. {@code vehicle:{id}:state} 형태다(워커 소유). */
		public String vehicleStateKeyPrefix() {
			return prefix() + "vehicle:";
		}

		public String vehicleStateKeySuffix() {
			return ":state";
		}

		public String vehicleStateKey(String vehicleId) {
			return vehicleStateKeyPrefix() + vehicleId + vehicleStateKeySuffix();
		}

		public String coneKey(String emergencyId) {
			return String.format(coneKeyPattern, emergencyId);
		}

		public String alertKey(String emergencyId) {
			return String.format(alertKeyPattern, emergencyId);
		}

		private String prefix() {
			return keyPrefix == null ? "" : keyPrefix;
		}
	}

	/**
	 * @param activeTtlMs             수신 시각이 이보다 오래된 차량은 활성 목록에서 제외한다.
	 *                                Location Worker의 {@code INACTIVE_TTL_MS}와 같은 값이어야 한다.
	 *                                워커가 이 기준으로 셀 ZSET에서 빼고 {@code status=INACTIVE}를
	 *                                찍으므로, 값이 다르면 양쪽 판정이 어긋난다.
	 * @param defaultDensityResolution 밀도 집계의 기본 H3 해상도.
	 */
	public record Vehicle(long activeTtlMs, int defaultDensityResolution) {
	}

	/**
	 * 차량 알림 스트림 설정. 쓰는 쪽은 Alert Worker, 같은 스트림을 ws-server도 소비한다
	 * (관제 API는 소비자 그룹을 쓰지 않으므로 서로 가로채지 않는다).
	 *
	 * @param enabled        false면 스트림을 따라 읽지 않는다(조회는 빈 결과).
	 * @param stream         알림 이벤트 스트림 키. ws-server의 {@code EVACS_ALERT_STREAM}과 같은 값이어야 한다.
	 * @param pollIntervalMs 스트림 조회 주기. 대시보드 폴링 주기보다 짧아야 표시가 밀리지 않는다.
	 * @param ttlMs          {@code expiresAt}이 없는 알림을 발생 시각 기준으로 유지하는 시간.
	 *                       해제 이벤트가 유실돼도 화면에 알림이 영구히 남지 않게 한다.
	 * @param bootstrapCount 기동 직후 현재 상태를 복원하려고 되감아 읽는 최근 엔트리 수.
	 * @param batchSize      한 번의 XREAD로 읽을 엔트리 수.
	 */
	public record Alert(
			boolean enabled,
			String stream,
			long pollIntervalMs,
			long ttlMs,
			int bootstrapCount,
			int batchSize) {
	}

	/**
	 * @param enabled        false면 스케줄러가 돌지 않는다(테스트에서 수동 호출용).
	 * @param pollIntervalMs 발행 주기.
	 * @param batchSize      한 주기에 선점할 이벤트 수.
	 * @param maxAttempts    이 횟수만큼 실패하면 FAILED로 두고 더 재시도하지 않는다.
	 */
	public record Outbox(boolean enabled, long pollIntervalMs, int batchSize, int maxAttempts) {
	}

	/**
	 * SUMO 모드 설정. {@code isSumo=true}로 들어온 긴급상황을 SUMO 시뮬레이터(app.py)에
	 * 투입할 때 쓴다.
	 *
	 * @param enabled        false면 SUMO 모드 요청을 거부한다(400). 실차 운영 배포의 기본값.
	 * @param baseUrl        SUMO 시뮬레이터 app.py 주소. {@code POST {baseUrl}/api/emergency}를 호출한다.
	 * @param defaultDepotId 요청에 sumoDepotId가 없을 때 쓸 차고지 ID({@code GET /api/depots}의 값).
	 * @param timeoutMs      app.py는 투입 결과를 최대 4초 기다렸다가 202를 주므로 그보다 넉넉해야 한다.
	 */
	public record Sumo(boolean enabled, String baseUrl, String defaultDepotId, long timeoutMs) {
	}
}
