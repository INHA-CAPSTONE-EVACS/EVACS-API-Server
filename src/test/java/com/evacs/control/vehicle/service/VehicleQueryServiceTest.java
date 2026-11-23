package com.evacs.control.vehicle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.common.geo.H3Support;
import com.evacs.control.vehicle.domain.VehicleCountSummary;
import com.evacs.control.vehicle.domain.VehicleState;
import com.evacs.control.vehicle.dto.DensityQueryDto;
import com.evacs.control.vehicle.dto.DensityResponse;
import com.evacs.control.vehicle.repository.RedisVehicleRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Redis 없이 가공 로직만 검증한다. */
@ExtendWith(MockitoExtension.class)
class VehicleQueryServiceTest {

	@Mock
	private RedisVehicleRepository vehicleRepository;

	private VehicleQueryService service;

	@BeforeEach
	void setUp() {
		EvacsProperties properties = new EvacsProperties(
				new EvacsProperties.Redis("test:", "c:%s", "a:%s", "s", 100),
				new EvacsProperties.Vehicle(30_000, 9),
				new EvacsProperties.Outbox(false, 1000, 10, 3));
		service = new VehicleQueryService(vehicleRepository, new H3Support(), properties);
	}

	private VehicleState state(String id, String type, double lat, double lng) {
		Instant now = Instant.now();
		return new VehicleState(
				id, type, lat, lng, 10, 90, "8930e0ab12fffff", 1, now, now, 1, "ACTIVE");
	}

	@Test
	@DisplayName("가까운 차량은 같은 H3 셀로, 먼 차량은 다른 셀로 집계된다")
	void densityGroupsByCell() {
		when(vehicleRepository.findVehicles(any())).thenReturn(List.of(
				state("CAR_1", "NORMAL", 37.450000, 126.650000),
				state("CAR_2", "NORMAL", 37.450010, 126.650010),
				state("AMB_1", "EMERGENCY", 37.550000, 126.750000)));

		DensityResponse response = service.findDensity(
				new DensityQueryDto(null, null, null, null, 9, null));

		assertThat(response.resolution()).isEqualTo(9);
		assertThat(response.cellCount()).isEqualTo(2);
		// 차량 수 내림차순이므로 2대가 모인 셀이 먼저다.
		DensityResponse.Cell dense = response.cells().get(0);
		assertThat(dense.vehicleCount()).isEqualTo(2);
		assertThat(dense.normalCount()).isEqualTo(2);
		assertThat(dense.emergencyCount()).isZero();
		assertThat(response.cells().get(1).emergencyCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("뒤집힌 좌표 범위는 빈 결과가 아니라 400이다")
	void rejectsInvertedBounds() {
		DensityQueryDto query = new DensityQueryDto(38.0, 37.0, null, null, 9, null);
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.findDensity(query)))
				.hasMessageContaining("minLat");
	}

	@Test
	@DisplayName("요약은 저장소 집계를 그대로 옮긴다")
	void summaryMapsCounts() {
		when(vehicleRepository.countByType()).thenReturn(new VehicleCountSummary(5, 3, 2, 0, 7));

		var summary = service.getSummary();

		assertThat(summary.total()).isEqualTo(5);
		assertThat(summary.emergency()).isEqualTo(2);
		assertThat(summary.stale()).isEqualTo(7);
	}
}
