package com.evacs.control.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 접근 설정.
 *
 * <p>값은 ws-server/Worker가 쓴 JSON 문자열이므로 직렬화를 Spring에 맡기지 않고
 * {@link StringRedisTemplate}로 문자열을 그대로 읽은 뒤 Jackson으로 직접 파싱한다.
 * JSON 직렬화기를 끼우면 타입 헤더가 붙어 다른 언어로 쓴 값과 호환되지 않는다.
 */
@Configuration
public class RedisConfig {

	@Bean
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}

	/**
	 * 활성 차량 상태를 한 번의 왕복으로 읽는 스크립트.
	 *
	 * <p>{@link DefaultRedisScript}는 SHA를 캐시해 EVALSHA로 보내고, NOSCRIPT가 오면 자동으로
	 * EVAL로 되돌린다. 그래서 Redis가 재시작해도 따로 로드할 필요가 없다.
	 */
	@Bean
	public RedisScript<List> activeVehicleStatesScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/active-vehicle-states.lua"));
		script.setResultType(List.class);
		return script;
	}

	/** Redis payload 전용 매퍼. 알 수 없는 필드가 추가돼도 깨지지 않게 둔다. */
	@Bean
	public ObjectMapper evacsObjectMapper() {
		return JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.serializationInclusion(JsonInclude.Include.NON_NULL)
				.build();
	}
}
