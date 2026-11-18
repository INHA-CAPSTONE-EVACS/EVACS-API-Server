package com.evacs.control.outbox.repository;

import com.evacs.control.outbox.domain.OutboxEvent;
import com.evacs.control.outbox.domain.OutboxStatus;
import com.evacs.control.outbox.repository.jpa.OutboxJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 이벤트를 영속화하고 발행 상태를 관리한다.
 *
 * <p>선점(claim)은 {@code FOR UPDATE SKIP LOCKED}로 한다. 프로세서 인스턴스가 둘 이상이어도
 * 같은 이벤트를 두 번 집지 않고, 남이 잡은 행에서 기다리지도 않는다.
 */
@Repository
public class OutboxRepository {

	/** 선점 후 이 시간이 지나도 결과가 안 찍히면 죽은 프로세서로 보고 회수한다. */
	private static final long STALE_CLAIM_SECONDS = 60;

	private static final String SELECT_CLAIMABLE =
			"""
			SELECT id FROM outbox_event
			 WHERE status = 'PENDING'
			    OR (status = 'PROCESSING' AND claimed_at < :cutoff)
			 ORDER BY created_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""";

	private static final String MARK_CLAIMED =
			"""
			UPDATE outbox_event
			   SET status = 'PROCESSING', attempts = attempts + 1, claimed_at = :now
			 WHERE id IN (:ids)
			""";

	private final OutboxJpaRepository jpa;

	@PersistenceContext
	private EntityManager em;

	public OutboxRepository(OutboxJpaRepository jpa) {
		this.jpa = jpa;
	}

	/** 신규 이벤트를 Outbox 테이블에 저장. 호출부(명령 서비스)의 트랜잭션에 참여한다. */
	public OutboxEvent save(OutboxEvent event) {
		return jpa.save(event);
	}

	/**
	 * 발행 대기 이벤트를 조회하고 처리 대상으로 선점한다.
	 *
	 * <p>반드시 독립 트랜잭션으로 묶는다. 선점 표시가 커밋돼야 다른 인스턴스가 같은 행을
	 * 건너뛸 수 있고, 발행 자체는 트랜잭션 밖(네트워크 I/O)에서 해야 DB 커넥션을 붙들지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<OutboxEvent> claimPending(int limit) {
		Instant now = Instant.now();
		Query select = em.createNativeQuery(SELECT_CLAIMABLE);
		select.setParameter("cutoff", now.minusSeconds(STALE_CLAIM_SECONDS));
		select.setParameter("limit", limit);
		// id 컬럼은 char(36)이므로 네이티브 조회 결과는 UUID가 아니라 String으로 온다.
		List<UUID> ids = select.getResultList().stream().map(OutboxRepository::toUuid).toList();
		if (ids.isEmpty()) {
			return List.of();
		}

		Query update = em.createNativeQuery(MARK_CLAIMED);
		update.setParameter("now", now);
		// 네이티브 쿼리에는 매핑 정보가 없다. UUID 객체를 그대로 넘기면 드라이버가
		// 바이너리로 바인딩해 char(36) 컬럼과 어긋나므로 문자열로 넘긴다.
		update.setParameter("ids", ids.stream().map(UUID::toString).toList());
		update.executeUpdate();
		// 위 네이티브 UPDATE는 영속성 컨텍스트를 모른다. 캐시된 낡은 엔티티를 읽지 않도록 비운다.
		em.clear();

		List<OutboxEvent> events = jpa.findAllById(ids);
		events.sort(Comparator.comparing(OutboxEvent::getCreatedAt));
		return events;
	}

	private static UUID toUuid(Object raw) {
		if (raw instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(raw.toString());
	}

	/** 브로커 발행 성공이 확인된 이벤트의 상태 변경. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPublished(UUID eventId, String messageId) {
		em.createQuery(
						"""
						UPDATE OutboxEvent e
						   SET e.status = :status, e.messageId = :messageId,
						       e.publishedAt = :now, e.lastError = NULL
						 WHERE e.id = :id
						""")
				.setParameter("status", OutboxStatus.PUBLISHED)
				.setParameter("messageId", messageId)
				.setParameter("now", Instant.now())
				.setParameter("id", eventId)
				.executeUpdate();
	}

	/**
	 * 발행 실패 정보 저장 및 재시도 상태 관리.
	 *
	 * <p>재시도 한도 안이면 PENDING으로 되돌려 다음 주기에 다시 집히게 하고,
	 * 한도를 넘기면 FAILED로 두고 더 집지 않는다 — 깨진 이벤트 하나가 뒤의 모든 이벤트를
	 * 막는 상황(head-of-line blocking)을 피한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(UUID eventId, String error, int maxAttempts) {
		OutboxEvent event = jpa.findById(eventId).orElse(null);
		if (event == null) {
			return;
		}
		OutboxStatus next = event.getAttempts() >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
		em.createQuery(
						"UPDATE OutboxEvent e SET e.status = :status, e.lastError = :error WHERE e.id = :id")
				.setParameter("status", next)
				.setParameter("error", truncate(error))
				.setParameter("id", eventId)
				.executeUpdate();
	}

	private String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 1000 ? error : error.substring(0, 1000);
	}
}
