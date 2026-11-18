package com.evacs.control.outbox.domain;

/**
 * 긴급상황 관련 이벤트 종류. Emergency Worker가 이 값으로 분기한다.
 *
 * <p>문자열을 그대로 쓰지 않고 enum으로 둔 이유는 오타가 나도 Worker가 조용히 무시해 버리기 때문이다.
 */
public enum EmergencyEventType {
	/** 긴급상황 생성. Worker가 cone 계산과 알림 전파를 시작한다. */
	EMERGENCY_CREATED,
	/** 긴급상황 종료. Worker가 알림을 해제하고 cone을 정리한다. */
	EMERGENCY_ENDED
}
