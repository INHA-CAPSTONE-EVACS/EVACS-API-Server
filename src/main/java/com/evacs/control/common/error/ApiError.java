package com.evacs.control.common.error;

import java.time.Instant;
import java.util.List;

/** 모든 오류 응답의 공통 형태. */
public record ApiError(String code, String message, List<String> details, Instant timestamp) {

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, null, Instant.now());
	}

	public static ApiError of(String code, String message, List<String> details) {
		return new ApiError(code, message, details, Instant.now());
	}
}
