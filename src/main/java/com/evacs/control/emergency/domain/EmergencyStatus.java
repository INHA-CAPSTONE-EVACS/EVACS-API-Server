package com.evacs.control.emergency.domain;

/** 긴급상황 상태. DB의 CHECK 제약과 값이 같아야 한다. */
public enum EmergencyStatus {
	/** 진행 중. 차량당 1건만 존재할 수 있다(부분 유니크 인덱스로 강제). */
	ACTIVE,
	/** 종료됨. */
	ENDED
}
