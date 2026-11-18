package com.evacs.control.common.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Controller 밖으로 나온 예외를 공통 형태로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getCode(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		List<String> details = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> e.getField() + ": " + e.getDefaultMessage())
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.of("VALIDATION_FAILED", "요청 값이 올바르지 않습니다", details));
	}

	/**
	 * 차량당 진행 중 긴급상황 1건 제약(부분 유니크 인덱스) 위반을 409로 돌린다.
	 * findActiveByVehicleId() 검사와 INSERT 사이의 경합은 애플리케이션 락이 아니라 DB가 막는다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex) {
		log.warn("무결성 제약 위반", ex);
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiError.of("CONFLICT", "이미 진행 중인 긴급상황이 있거나 중복된 데이터입니다"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("처리되지 않은 예외", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of("INTERNAL_ERROR", "서버 내부 오류"));
	}
}
