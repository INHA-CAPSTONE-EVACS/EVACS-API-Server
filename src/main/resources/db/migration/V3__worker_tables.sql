-- Emergency Worker / Alert Worker 테이블을 관제 API의 Flyway 이력으로 흡수한다.
--
-- 지금까지 이 세 테이블은 워커 레포의 손으로 돌리는 스크립트였다
-- (Emergency-Worker/migrations/001_dispatch_route.sql, Alert-Worker/migrations/001_alert_delivery_log.sql).
-- 같은 `evacs` 스키마를 쓰는데 이력 테이블이 없어서 "어디까지 적용됐는지"를 알 수 없었다.
-- 테이블 정의를 옮기고 트리거는 선택 적용 스크립트로 분리했다. 이 마이그레이션 이후로는
-- 워커 쪽 001_*.sql을 중복 적용하지 않는다.
-- 스키마 비교 근거는 레포 루트 schema-diff.md.
--
-- 이미 워커 스크립트로 만들어 둔 DB에도 그대로 올라가도록 CREATE TABLE IF NOT EXISTS로 쓴다.
-- 관제 API는 이 테이블들을 JPA로 매핑하지 않는다(ddl-auto: validate는 매핑된 테이블만 본다).
--
-- 타입 규칙은 V1과 같다. 다만 워커 테이블은 시각을 DATETIME(3)으로 쓴다(워커가 ms 정밀도로 기록).
--   UUID      -> CHAR(36)
--   TIMESTAMP -> DATETIME(3), UTC
--   JSONB     -> JSON
--   ENUM      -> VARCHAR + CHECK
--
-- dispatch_id에는 FK를 걸지 않는다. 실제 값은 emergency.id지만, 설계서 형식의 출동 ID(DSP-001)는
-- UUID가 아니고 워커는 emergency 테이블의 소유자가 아니다.
-- 길이가 테이블마다 다른 것(VARCHAR(64) vs VARCHAR(128))도 워커 스크립트 그대로다.
-- 통일은 워커 코드와 함께 바꿔야 하므로 별도 마이그레이션으로 미룬다(schema-diff.md 남은 정리 사항).

-- Emergency Worker: 출동별 경로 이력 (설계서 ERD c.3). append-only.
CREATE TABLE IF NOT EXISTS dispatch_route (
    route_id           CHAR(36)     NOT NULL,
    dispatch_id        VARCHAR(64)  NOT NULL,
    route_version      INT          NOT NULL,
    route_coordinates  JSON         NOT NULL,
    road_segments      JSON         NOT NULL,
    estimated_duration INT          NOT NULL,
    change_reason      VARCHAR(64)  NULL,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (route_id),
    CONSTRAINT dispatch_route_version_unique UNIQUE (dispatch_id, route_version),
    CONSTRAINT dispatch_route_version_check CHECK (route_version > 0),
    CONSTRAINT dispatch_route_duration_check CHECK (estimated_duration >= 0)
) ENGINE = InnoDB;

-- 경로 이력은 append-only다(설계서 ERD c.3). 그 강제를 **트리거로 하지 않는다.**
-- 바이너리 로깅이 켜진 MySQL에서 CREATE TRIGGER는 SUPER를 요구하고(ERROR 1419), RDS 마스터
-- 계정에는 SUPER가 없다 → 마이그레이션이 여기서 깨지면 앱이 아예 부팅하지 못한다.
-- 그래서 수정·삭제 금지는 Emergency Worker(쓰기 경로가 INSERT 하나뿐)가 책임지고,
-- DB로 한 번 더 막고 싶은 환경에서만 db/optional/dispatch_route_append_only.sql을 손으로 적용한다.

-- Emergency Worker: 경로 저장과 경로 변경 이벤트 발행을 한 트랜잭션으로 묶는 Outbox.
-- (설계서 ERD에는 없고 컴포넌트 설계의 OutboxRepository/OutboxProcessor가 필요로 한다.)
CREATE TABLE IF NOT EXISTS emergency_route_outbox (
    outbox_id    BIGINT       NOT NULL AUTO_INCREMENT,
    route_id     CHAR(36)     NOT NULL,
    event_key    VARCHAR(255) NOT NULL,
    event        JSON         NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    delivered_at DATETIME(3)  NULL,
    message_id   VARCHAR(64)  NULL,
    PRIMARY KEY (outbox_id),
    -- MySQL에는 부분 인덱스가 없다. delivered_at을 선두에 두면 미발행(NULL) 구간만 outbox 순서로 읽는다.
    -- CREATE INDEX에는 IF NOT EXISTS가 없어서, 이미 만들어진 DB에서도 깨지지 않도록 테이블 안에 둔다.
    KEY emergency_route_outbox_pending (delivered_at, outbox_id),
    CONSTRAINT emergency_route_outbox_event_key_unique UNIQUE (event_key),
    CONSTRAINT emergency_route_outbox_route_fk FOREIGN KEY (route_id) REFERENCES dispatch_route (route_id)
) ENGINE = InnoDB;

-- Alert Worker: 알림 발송 이력 (설계서 ERD c.4). 알림 1건(발송~해제)당 1행.
-- 해제 뒤 같은 alert_id로 다시 발송되면 새 alert_log_id로 새 행이 생긴다.
-- target_vehicle_id가 UUID가 아닌 것은 SUMO 차량 ID가 EVACS_CAR_000000 형식이기 때문이다.
CREATE TABLE IF NOT EXISTS alert_delivery_log (
    alert_log_id              CHAR(36)     NOT NULL,
    alert_id                  VARCHAR(128) NOT NULL,
    alert_version             INT          NOT NULL,
    dispatch_id               VARCHAR(128) NOT NULL,
    target_vehicle_id         VARCHAR(128) NOT NULL,
    selection_reason          VARCHAR(64)  NOT NULL,
    estimated_arrival_seconds INT          NULL,
    alert_status              VARCHAR(16)  NOT NULL,
    first_sent_at             DATETIME(3)  NULL,
    acknowledged_at           DATETIME(3)  NULL,
    cleared_at                DATETIME(3)  NULL,
    expires_at                DATETIME(3)  NULL,
    clear_reason              VARCHAR(64)  NULL,
    PRIMARY KEY (alert_log_id),
    KEY alert_delivery_log_dispatch (dispatch_id),
    KEY alert_delivery_log_alert (alert_id),
    CONSTRAINT alert_delivery_log_version_check CHECK (alert_version > 0),
    CONSTRAINT alert_delivery_log_status_check
        CHECK (alert_status IN ('PENDING', 'SENT', 'ACKNOWLEDGED', 'CLEARED', 'EXPIRED', 'FAILED'))
) ENGINE = InnoDB;
