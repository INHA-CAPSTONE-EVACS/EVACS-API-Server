package com.evacs.control.emergency.repository;

import com.evacs.control.common.config.EvacsProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 알림 수신이 확인된 차량 ID를 redis에서 조회한다.
 *
 * <p>Emergency Worker가 알림 전달에 성공할 때마다 Set에 vehicleId를 넣는다.
 * Set인 이유는 같은 차량에 알림이 여러 번 가도 "수신 확인된 차량"은 한 번만 세야 하기 때문이다.
 */
@Repository
public class RedisAlertRepository {

	private final StringRedisTemplate redis;
	private final EvacsProperties properties;

	public RedisAlertRepository(StringRedisTemplate redis, EvacsProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** 알림 수신이 확인된 차량 ID 조회. */
	public List<String> findRecievedVehicleId(String emergencyId) {
		Set<String> members = redis.opsForSet().members(properties.redis().alertKey(emergencyId));
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>(members);
		ids.sort(String::compareTo);
		return ids;
	}
}
