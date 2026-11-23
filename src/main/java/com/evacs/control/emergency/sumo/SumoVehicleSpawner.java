package com.evacs.control.emergency.sumo;

import com.evacs.control.common.config.EvacsProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/**
 * SUMO 모드 긴급상황에 대해 시뮬레이터(EVACS-SUMO-Continuous의 app.py)에 긴급차량을 투입한다.
 *
 * <p>{@code POST {base-url}/api/emergency}는 "차고지 → 목적지 최단경로"로 차량 1대를 만든다.
 * {@code vehicleId}를 그대로 넘기므로 시뮬레이션 차량 ID = 긴급상황의 vehicleId가 되고,
 * SUMO 어댑터가 신규 차량으로 잡아 자동으로 Gateway에 붙인다(어댑터는 손댈 필요 없다).
 *
 * <p><b>AFTER_COMMIT에 호출한다.</b> 트랜잭션 안에서 부르면 롤백됐는데 시뮬레이터에는 차량이
 * 남는 상태가 되고, 되돌릴 방법이 없다. 반대로 커밋 후 투입이 실패하면 긴급상황 행만 남는다 —
 * 재시도하지 않고 경고만 남긴다(같은 vehicleId로 다시 투입하면 app.py가 409를 준다).
 */
@Component
public class SumoVehicleSpawner {

	private static final Logger log = LoggerFactory.getLogger(SumoVehicleSpawner.class);

	private final RestClient client;

	public SumoVehicleSpawner(EvacsProperties properties, RestClient.Builder builder) {
		EvacsProperties.Sumo sumo = properties.sumo();
		Duration timeout = Duration.ofMillis(sumo.timeoutMs());
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(timeout);
		requestFactory.setReadTimeout(timeout);
		this.client = builder.baseUrl(sumo.baseUrl()).requestFactory(requestFactory).build();
	}

	/** 트랜잭션 커밋 후 투입. 실패는 요청 응답에 영향을 주지 않는다(이미 201을 돌려준 뒤다). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSpawnRequested(SumoSpawnRequested event) {
		try {
			Map<?, ?> body = spawn(event);
			log.info("SUMO 긴급차량 투입: emergencyId={} vehicleId={} 응답={}",
					event.emergencyId(), event.vehicleId(), body);
		} catch (Exception e) {
			// 시뮬레이터가 꺼져 있거나 일시정지(409)·경로 없음(400)인 경우. 긴급상황은 이미 생성됐다.
			log.error("SUMO 긴급차량 투입 실패: emergencyId={} vehicleId={} error={}",
					event.emergencyId(), event.vehicleId(), e.toString());
		}
	}

	/** 실제 호출. 예외를 그대로 던지므로 테스트에서 실패를 단정할 수 있다. */
	public Map<?, ?> spawn(SumoSpawnRequested event) {
		return client.post()
				.uri("/api/emergency")
				.body(Map.of(
						"depotId", event.depotId(),
						"destinationLat", event.destinationLat(),
						"destinationLng", event.destinationLng(),
						"vehicleId", event.vehicleId()))
				.retrieve()
				.body(Map.class);
	}
}
