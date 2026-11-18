package com.evacs.control.emergency.repository;

import com.evacs.control.common.config.EvacsProperties;
import com.evacs.control.emergency.domain.ConeSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 긴급차량의 H3 영향 셀(moving cone) 정보를 redis에서 조회한다.
 *
 * <p>쓰는 쪽은 Emergency Worker다. 긴급차량이 움직일 때마다 갱신되는 휘발성 상태라
 * MySQL에 두지 않는다. 값이 없으면 null을 돌려 서비스가 "아직 계산 전"으로 응답하게 한다.
 */
@Repository
public class RedisConeRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisConeRepository.class);

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final EvacsProperties properties;

	public RedisConeRepository(
			StringRedisTemplate redis, ObjectMapper evacsObjectMapper, EvacsProperties properties) {
		this.redis = redis;
		this.objectMapper = evacsObjectMapper;
		this.properties = properties;
	}

	/** 특정 긴급상황의 최신 영향 셀 버전 조회. 없으면 null. */
	public ConeSnapshot findByEmergencyId(String emergencyId) {
		String raw = redis.opsForValue().get(properties.redis().coneKey(emergencyId));
		if (raw == null) {
			return null;
		}
		try {
			return objectMapper.readValue(raw, ConeSnapshot.class);
		} catch (Exception e) {
			log.warn("cone 스냅샷 파싱 실패: emergencyId={} error={}", emergencyId, e.getMessage());
			return null;
		}
	}
}
