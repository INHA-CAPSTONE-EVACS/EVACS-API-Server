-- EVACS Control API Server 영속 스키마 (MySQL 8.0+).
-- 실시간 차량 위치·cone·알림 수신 상태는 Redis에 있고, 여기에는 긴급상황 영속 데이터만 둔다.
--
-- 타입은 Hibernate가 이 엔티티들로 생성하는 DDL과 정확히 같게 맞췄다(ddl-auto: validate).
--   UUID        -> CHAR(36)    (@JdbcTypeCode(SqlTypes.CHAR); binary(16)은 콘솔에서 읽기 어렵다)
--   Instant     -> DATETIME(6) (MySQL은 TIMESTAMPTZ가 없다. 서버/JDBC 타임존을 UTC로 고정해 쓴다)
--   JSON payload-> JSON        (Postgres jsonb 대체)

CREATE TABLE emergency (
    id              CHAR(36)     NOT NULL,
    vehicle_id      VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    emergency_type  VARCHAR(32)  NOT NULL,
    origin_lat      DOUBLE       NULL,
    origin_lng      DOUBLE       NULL,
    destination_lat DOUBLE       NULL,
    destination_lng DOUBLE       NULL,
    description     TEXT         NULL,
    started_at      DATETIME(6)  NOT NULL,
    ended_at        DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 한 긴급차량이 동시에 두 건의 진행 중 긴급상황을 가질 수 없다.
    -- MySQL에는 부분 유니크 인덱스(WHERE status='ACTIVE')가 없으므로,
    -- ACTIVE일 때만 vehicle_id를 갖고 그 외에는 NULL인 생성 컬럼에 유니크를 건다
    -- (유니크 인덱스는 NULL을 중복으로 보지 않는다). findActiveByVehicleId() 검사와
    -- 경합해도 DB가 최종적으로 막아 준다.
    active_vehicle_id VARCHAR(64)
        GENERATED ALWAYS AS (IF(status = 'ACTIVE', vehicle_id, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT emergency_status_check CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT emergency_one_active_per_vehicle UNIQUE (active_vehicle_id)
) ENGINE = InnoDB;

CREATE INDEX emergency_status_started_at_idx ON emergency (status, started_at DESC);

CREATE TABLE emergency_route (
    id            CHAR(36)    NOT NULL,
    emergency_id  CHAR(36)    NOT NULL,
    `sequence`    INT         NOT NULL,
    latitude      DOUBLE      NOT NULL,
    longitude     DOUBLE      NOT NULL,
    h3_index      VARCHAR(24) NULL,
    eta_seconds   INT         NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT emergency_route_seq_unique UNIQUE (emergency_id, `sequence`),
    CONSTRAINT emergency_route_emergency_fk
        FOREIGN KEY (emergency_id) REFERENCES emergency (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- (emergency_id, sequence) 유니크가 이미 선두 컬럼 인덱스 역할을 하므로 별도 인덱스는 두지 않는다.

-- Emergency Worker가 알림을 보낼 때마다 남기는 전송 이력.
-- API 서버는 조회만 한다(설계서 EventLogRepository는 findByEmergencyId()만 가진다).
CREATE TABLE event_log (
    id           CHAR(36)    NOT NULL,
    emergency_id CHAR(36)    NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    vehicle_id   VARCHAR(64) NULL,
    payload      JSON        NULL,
    occurred_at  DATETIME(6) NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT event_log_emergency_fk
        FOREIGN KEY (emergency_id) REFERENCES emergency (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX event_log_emergency_idx ON event_log (emergency_id, occurred_at DESC);

-- Outbox: 긴급상황 상태 변경과 같은 트랜잭션에서 기록되고,
-- OutboxProcessor가 주기적으로 조회해 브로커로 발행한다.
CREATE TABLE outbox_event (
    id             CHAR(36)      NOT NULL,
    aggregate_type VARCHAR(32)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(64)   NOT NULL,
    payload        TEXT          NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000) NULL,
    message_id     VARCHAR(64)   NULL,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    claimed_at     DATETIME(6)   NULL,
    published_at   DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT outbox_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE = InnoDB;

-- claimPending()의 FOR UPDATE SKIP LOCKED 스캔용 인덱스.
-- MySQL에는 부분 인덱스가 없어 전체 인덱스로 둔다. PENDING/PROCESSING이 선두 컬럼이라
-- 선점 조회는 여전히 해당 구간만 훑는다. PROCESSING을 포함해야 발행 도중 죽은 행을
-- claimed_at 기준으로 회수할 수 있다.
CREATE INDEX outbox_claimable_idx ON outbox_event (status, created_at);
