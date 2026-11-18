package com.evacs.control.vehicle.domain;

/**
 * 활성 차량 수 집계. 설계서 {@code RedisVehicleRepository.countByType()}의 반환 타입이다.
 *
 * @param total     신선한(활성) 차량 총 수
 * @param normal    일반차량 수
 * @param emergency 긴급차량 수
 * @param unknown   vehicleType을 해석할 수 없는 차량 수
 * @param stale     오래돼 활성에서 제외된 항목 수(스냅샷 해시에 남은 잔재)
 */
public record VehicleCountSummary(long total, long normal, long emergency, long unknown, long stale) {
}
