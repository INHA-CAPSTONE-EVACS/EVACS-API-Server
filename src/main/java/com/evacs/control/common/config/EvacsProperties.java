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
	 * @param locationSnapshotKey 차량별 최신 위치 해시. ws-server가 {@code HSET}으로 갱신한다.
	 * @param coneKeyPattern      긴급차량 영향 셀(cone) 키 패턴. {@code %s} 자리에 emergencyId.
	 * @param alertKeyPattern     알림 수신이 확인된 차량 ID 집합 키 패턴. {@code %s} 자리에 emergencyId.
	 * @param emergencyStream     긴급상황 이벤트 발행 스트림. Emergency Worker가 소비한다.
	 */
	public record Redis(
			String locationSnapshotKey,
			String coneKeyPattern,
			String alertKeyPattern,
			String emergencyStream,
			long emergencyStreamMaxlen) {

		public String coneKey(String emergencyId) {
			return String.format(coneKeyPattern, emergencyId);
		}

		public String alertKey(String emergencyId) {
			return String.format(alertKeyPattern, emergencyId);
		}
	}

	/**
	 * @param staleAfterSeconds       스냅샷 해시에는 TTL이 없다. occurredAt이 이보다 오래되면
	 *                                떠난 차량으로 보고 활성 목록에서 제외한다.
	 * @param defaultDensityResolution 밀도 집계의 기본 H3 해상도.
	 */
	public record Vehicle(long staleAfterSeconds, int defaultDensityResolution) {
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
