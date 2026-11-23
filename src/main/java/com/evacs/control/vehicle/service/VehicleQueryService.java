package com.evacs.control.vehicle.service;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.common.error.ApiException;
import com.evacs.control.common.geo.H3Support;
import com.evacs.control.vehicle.domain.VehicleCountSummary;
import com.evacs.control.vehicle.domain.VehicleState;
import com.evacs.control.vehicle.domain.VehicleType;
import com.evacs.control.vehicle.dto.DensityQueryDto;
import com.evacs.control.vehicle.dto.DensityResponse;
import com.evacs.control.vehicle.dto.VehicleListResponse;
import com.evacs.control.vehicle.dto.VehicleQueryDto;
import com.evacs.control.vehicle.dto.VehicleResponse;
import com.evacs.control.vehicle.dto.VehicleSummaryResponse;
import com.evacs.control.vehicle.repository.RedisVehicleRepository;
import com.evacs.control.vehicle.repository.RedisVehicleRepository.VehicleQueryFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Redis에서 조회한 실시간 차량 정보를 관제 대시보드에 적합한 데이터로 가공한다.
 *
 * <p>조회 전용이라 트랜잭션이 없다. MySQL을 보지 않는다 — 실시간 위치는 Redis가 유일한 출처다.
 */
@Service
public class VehicleQueryService {

	private final RedisVehicleRepository vehicleRepository;
	private final H3Support h3;
	private final EvacsProperties properties;

	public VehicleQueryService(
			RedisVehicleRepository vehicleRepository, H3Support h3, EvacsProperties properties) {
		this.vehicleRepository = vehicleRepository;
		this.h3 = h3;
		this.properties = properties;
	}

	/** 조건에 맞는 차량 조회 및 응답 DTO 생성. */
	public VehicleListResponse findVehicles(VehicleQueryDto query) {
		validateBounds(query.minLat(), query.maxLat(), query.minLng(), query.maxLng());
		Instant now = Instant.now();
		Duration staleAfter = staleAfter();
		List<VehicleResponse> vehicles = vehicleRepository.findVehicles(toFilter(query)).stream()
				.map(state -> VehicleResponse.from(state, state.isStale(now, staleAfter)))
				.toList();
		return VehicleListResponse.of(vehicles);
	}

	/** 특정 차량의 위치·속도·방향 등 조회. */
	public VehicleResponse findVehicle(String vehicleId) {
		VehicleState state = vehicleRepository.findById(vehicleId);
		if (state == null) {
			throw ApiException.notFound("차량", vehicleId);
		}
		return VehicleResponse.from(state, state.isStale(Instant.now(), staleAfter()));
	}

	/** 활성 차량의 통계 정보 생성. */
	public VehicleSummaryResponse getSummary() {
		VehicleCountSummary summary = vehicleRepository.countByType();
		return VehicleSummaryResponse.from(summary);
	}

	/**
	 * 지정된 H3 영역의 활성 차량수 집계.
	 *
	 * <p>셀 계산은 조회 시점에 한다. 차량 위치가 1초 단위로 바뀌므로 셀을 미리 저장해도
	 * 곧 틀린 값이 되고, 해상도를 바꿔 보고 싶은 쪽은 대시보드다.
	 */
	public DensityResponse findDensity(DensityQueryDto query) {
		validateBounds(query.minLat(), query.maxLat(), query.minLng(), query.maxLng());
		int resolution = query.resolution() != null
				? query.resolution()
				: properties.vehicle().defaultDensityResolution();

		Map<String, long[]> counts = new LinkedHashMap<>(); // [전체, 일반, 긴급]
		for (VehicleState state : vehicleRepository.findVehicles(toFilter(query.toVehicleQuery()))) {
			String cell = h3.cellOf(state.latitude(), state.longitude(), resolution);
			long[] bucket = counts.computeIfAbsent(cell, k -> new long[3]);
			bucket[0]++;
			if (state.type() == VehicleType.EMERGENCY) {
				bucket[2]++;
			} else if (state.type() == VehicleType.NORMAL) {
				bucket[1]++;
			}
		}

		List<DensityResponse.Cell> cells = new ArrayList<>(counts.size());
		for (Map.Entry<String, long[]> entry : counts.entrySet()) {
			double[] center = h3.centerOf(entry.getKey());
			long[] bucket = entry.getValue();
			cells.add(new DensityResponse.Cell(
					entry.getKey(), center[0], center[1], bucket[0], bucket[1], bucket[2]));
		}
		cells.sort(Comparator.comparingLong(DensityResponse.Cell::vehicleCount).reversed());
		return DensityResponse.of(resolution, cells);
	}

	private VehicleQueryFilter toFilter(VehicleQueryDto query) {
		return new VehicleQueryFilter(
				query.minLat(),
				query.maxLat(),
				query.minLng(),
				query.maxLng(),
				query.type(),
				query.hasTypeFilter(),
				query.includeStaleOrDefault());
	}

	/** 좌표 범위가 뒤집혀 들어오면 결과가 조용히 비어 버리므로 400으로 막는다. */
	private void validateBounds(Double minLat, Double maxLat, Double minLng, Double maxLng) {
		if (minLat != null && maxLat != null && minLat > maxLat) {
			throw ApiException.badRequest("minLat이 maxLat보다 큽니다");
		}
		if (minLng != null && maxLng != null && minLng > maxLng) {
			throw ApiException.badRequest("minLng이 maxLng보다 큽니다");
		}
	}

	private Duration staleAfter() {
		return Duration.ofMillis(properties.vehicle().activeTtlMs());
	}
}
