package com.evacs.control.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code evacs.*} 설정. 환경변수 읽기와 기본값은 이 클래스와 application.yml에만 둔다.
 *
 * <p>Redis 키는 evacs-ws-server / Emergency Worker와 공유하는 계약이므로
 * 값이 어긋나면 조회가 조용히 빈 결과를 돌려준다. 반드시 같은 값을 쓸 것.
 */
@ConfigurationProperties(prefix = "evacs")
public record EvacsProperties(Redis redis, Vehicle vehicle, Outbox outbox) {

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
	 * @param enabled        false면 스케줄러가 돌지 않는다(테스트에서 수동 호출용).
	 * @param pollIntervalMs 발행 주기.
	 * @param batchSize      한 주기에 선점할 이벤트 수.
	 * @param maxAttempts    이 횟수만큼 실패하면 FAILED로 두고 더 재시도하지 않는다.
	 */
	public record Outbox(boolean enabled, long pollIntervalMs, int batchSize, int maxAttempts) {
	}
}
