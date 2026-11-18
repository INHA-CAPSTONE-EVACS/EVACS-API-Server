package com.evacs.control.outbox.domain;

/** Outbox 이벤트의 발행 상태. DB CHECK 제약과 값이 같아야 한다. */
public enum OutboxStatus {
	/** 발행 대기. */
	PENDING,
	/** 프로세서가 선점해 발행 중. 프로세스가 죽으면 이 상태로 남고, 일정 시간 뒤 재선점된다. */
	PROCESSING,
	/** 브로커 발행 성공이 확인됨. */
	PUBLISHED,
	/** 재시도 한도를 넘겨 포기함. 사람이 봐야 한다. */
	FAILED
}
