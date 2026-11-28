package com.evacs.control.alert.service;

import com.evacs.control.alert.domain.VehicleAlert;
import com.evacs.control.alert.repository.RedisYieldAlertRepository;
import com.evacs.control.common.config.EvacsProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 차량별 "지금 떠 있는 알림"을 들고 있는다.
 *
 * <p>스트림을 주기적으로 따라 읽어 발행(ISSUED)·경로 변경(ROUTE_UPDATED)은 차량의 최신 알림으로
 * 덮고, 해제(CLEARED)는 지운다. 조회 때마다 스트림을 다시 훑지 않는 이유는 대시보드가 1초 주기로
 * 폴링하기 때문이다 — 매 요청이 XREVRANGE 수백 건이면 Redis 왕복이 조회 수에 비례해 늘어난다.
 *
 * <p>상태는 메모리에만 둔다. 재시작하면 {@code bootstrap-count}만큼 최근 엔트리를 다시 읽어
 * 복원하므로 별도 저장소가 필요 없다. 수평 확장하면 인스턴스마다 같은 스트림을 각자 읽는다
 * (그룹을 쓰지 않으므로 서로 가로채지 않는다).
 */
@Service
public class VehicleAlertService {

	private static final Logger log = LoggerFactory.getLogger(VehicleAlertService.class);

	/** 한 주기에 이 횟수까지만 이어 읽는다. 적체가 심해도 주기 하나를 영원히 붙잡지 않는다. */
	private static final int MAX_DRAIN_ROUNDS = 10;

	private final RedisYieldAlertRepository alertRepository;
	private final EvacsProperties properties;

	private final Map<String, VehicleAlert> currentAlerts = new ConcurrentHashMap<>();
	private volatile String cursor;
	private volatile Instant lastPolledAt;

	public VehicleAlertService(
			RedisYieldAlertRepository alertRepository, EvacsProperties properties) {
		this.alertRepository = alertRepository;
		this.properties = properties;
	}

	/**
	 * 스트림 뒤를 따라 읽어 상태를 갱신한다.
	 *
	 * <p>fixedDelay라 이전 주기가 끝난 뒤 다음 주기가 시작된다. Redis가 잠깐 끊겨도
	 * 스케줄러를 죽이지 않고 다음 주기에 다시 시도한다(커서를 그대로 두므로 유실되지 않는다).
	 */
	@Scheduled(fixedDelayString = "${evacs.alert.poll-interval-ms:1000}")
	public void pollScheduled() {
		if (!properties.alert().enabled()) {
			return;
		}
		try {
			poll();
		} catch (Exception e) {
			log.warn("양보 알림 스트림 조회 실패: {}", e.toString());
		}
	}

	/** 한 주기 분량을 읽어 반영하고 반영한 이벤트 수를 돌려준다(테스트에서 직접 호출한다). */
	public synchronized int poll() {
		EvacsProperties.Alert config = properties.alert();
		int applied = 0;
		for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
			List<VehicleAlert> batch =
					cursor == null
							? alertRepository.findRecent(config.bootstrapCount())
							: alertRepository.findAfter(cursor, config.batchSize());
			if (batch.isEmpty()) {
				break;
			}
			for (VehicleAlert alert : batch) {
				apply(alert);
				if (alert.entryId() != null) {
					cursor = alert.entryId();
				}
			}
			applied += batch.size();
			// 커서를 세우지 못했으면(엔트리 id가 전부 비정상) 같은 구간을 무한히 다시 읽는다.
			if (cursor == null) {
				break;
			}
		}
		evictExpired(Instant.now());
		lastPolledAt = Instant.now();
		return applied;
	}

	/** 지금 알림이 떠 있는 차량 목록. 최근 발생이 먼저 온다. */
	public List<VehicleAlert> findAlerts() {
		evictExpired(Instant.now());
		List<VehicleAlert> alerts = new ArrayList<>(currentAlerts.values());
		alerts.sort(
				Comparator.comparing(
								VehicleAlert::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(VehicleAlert::vehicleId));
		return alerts;
	}

	/** 특정 차량에 떠 있는 알림. 없으면 null. */
	public VehicleAlert findAlert(String vehicleId) {
		evictExpired(Instant.now());
		return currentAlerts.get(vehicleId);
	}

	/** 마지막으로 스트림을 읽은 시각. 화면이 신선도를 판단하는 데 쓴다. */
	public Instant lastPolledAt() {
		return lastPolledAt;
	}

	/** 지금까지 읽은 마지막 entry id. 운영 점검용. */
	public String cursor() {
		return cursor;
	}

	private void apply(VehicleAlert alert) {
		if (alert.isCleared()) {
			currentAlerts.remove(alert.vehicleId());
			return;
		}
		// 같은 차량에 새 알림이 오면 최신 1건만 남긴다(버전이 올라간 재발행 포함).
		currentAlerts.put(alert.vehicleId(), alert);
	}

	/**
	 * 만료된 알림을 지운다. 해제 이벤트가 유실되거나 Worker가 죽어도 화면에 알림이 영구히
	 * 남지 않아야 한다.
	 */
	private void evictExpired(Instant now) {
		long ttlMs = properties.alert().ttlMs();
		currentAlerts.values().removeIf(alert -> alert.isExpired(now, ttlMs));
	}
}
