# 설계서 vs 현재 RDS 스키마 비교 (Emergency Worker / Alert Worker)

- 기준: 설계서 `상세설계서수정본_윰씨윰씨.pdf` ERD c.3, c.4
- 현재: Control API Server의 Flyway `V3__worker_tables.sql`
  (원본은 `Emergency-Worker/migrations/001_dispatch_route.sql` 커밋 `5bc676f`,
  `Alert-Worker/migrations/001_alert_delivery_log.sql` 커밋 `ec9e668` — 테이블 정의를 옮기고 트리거는 선택 적용으로 분리했다)

## 공통 변경

- DB 엔진: PostgreSQL → **MySQL 8.4**. 테이블은 Control API Server의 `evacs` DB를 같이 쓴다.
- 타입 매핑은 API Server와 같은 규칙을 따른다.

| 설계서 (PostgreSQL) | 현재 (MySQL) |
|---|---|
| UUID | CHAR(36) |
| TIMESTAMP | DATETIME(3), UTC로 저장 |
| JSONB | JSON |
| ENUM | VARCHAR + CHECK |

- `dispatch_id`에는 FK를 걸지 않는다. 출동은 API Server `emergency` 테이블의 `id`로 식별하는데, 그 테이블은 워커가 아니라 API Server 소유다.

---

## 1. `dispatch_route` (Emergency Worker, ERD c.3)

| 컬럼 | 설계서 | 현재 | 변경 내용 |
|---|---|---|---|
| route_id | UUID, PK, NOT NULL | CHAR(36), PK, NOT NULL | 타입만 바뀜 |
| dispatch_id | UUID, **FK**, NOT NULL | **VARCHAR(64)**, NOT NULL, **FK 없음** | emergency ID 사용, 소유 테이블이 API Server에 있음 |
| route_version | INT, NOT NULL | INT, NOT NULL, CHECK > 0 | 제약 추가 |
| route_coordinates | UUID → JSONB 권장, NOT NULL | JSON, NOT NULL | 설계서 권장대로 JSON |
| road_segments | JSONB, NOT NULL | JSON, NOT NULL | 타입만 바뀜 |
| estimated_duration | INT, NOT NULL | INT, NOT NULL, CHECK >= 0 | 제약 추가 |
| change_reason | VARCHAR, NULL | VARCHAR(64), NULL | 길이 지정 |
| created_at | TIMESTAMP, NOT NULL | DATETIME(3), NOT NULL | 밀리초 정밀도, UTC |

테이블 수준 추가 사항

- `UNIQUE (dispatch_id, route_version)`: 같은 출동에 같은 버전이 두 번 저장되지 않게 막는다.
- 트리거 `dispatch_route_no_update`, `dispatch_route_no_delete`: 설계서의 "기존 레코드 수정 금지"(append-only)를 DB에서 강제한다.
  - ⚠️ **마이그레이션에는 넣지 않는다.** 바이너리 로깅이 켜진 MySQL에서 `CREATE TRIGGER`는 SUPER를 요구하고(`ERROR 1419`) RDS 마스터 계정에는 SUPER가 없다 — V3에 넣었더니 RDS에서 앱이 부팅하지 못했다. `src/main/resources/db/optional/dispatch_route_append_only.sql`로 분리해 권한이 있는 환경에서만 손으로 적용한다.
  - 트리거가 없어도 쓰기 경로는 Emergency Worker의 INSERT 하나뿐이고 `UNIQUE (dispatch_id, route_version)`이 같은 버전 재저장을 막는다.

## 2. `emergency_route_outbox` (Emergency Worker, ERD에 없는 테이블)

설계서 컴포넌트 설계의 OutboxRepository/OutboxProcessor를 구현하는 데 필요한 테이블이다. 경로 저장과 이벤트 발행을 한 트랜잭션으로 묶는다.

| 컬럼 | 설계서 | 현재 | 용도 |
|---|---|---|---|
| outbox_id | 없음 | BIGINT AUTO_INCREMENT, PK | 발행 순서 |
| route_id | 없음 | CHAR(36), NOT NULL, FK → dispatch_route | 어느 경로에 대한 이벤트인지 |
| event_key | 없음 | VARCHAR(255), NOT NULL, UNIQUE | 같은 이벤트 중복 저장 방지 |
| event | 없음 | JSON, NOT NULL | 발행할 이벤트 내용 |
| created_at | 없음 | DATETIME(3), NOT NULL | 생성 시각 |
| delivered_at | 없음 | DATETIME(3), NULL | Redis 발행 완료 시각 (NULL이면 아직 발행 전) |
| message_id | 없음 | VARCHAR(64), NULL | Redis Stream 메시지 ID |

- 인덱스 `(delivered_at, outbox_id)`: 아직 발행 안 된 이벤트를 순서대로 찾는다. MySQL에는 부분 인덱스가 없어서 복합 인덱스로 대신한다.

## 3. `alert_delivery_log` (Alert Worker, ERD c.4)

| 컬럼 | 설계서 | 현재 | 변경 내용 |
|---|---|---|---|
| alert_log_id | UUID, PK, NOT NULL | CHAR(36), PK, NOT NULL | 타입만 바뀜 |
| **alert_id** | 없음 | VARCHAR(128), NOT NULL, 인덱스 | **추가.** (출동, 차량)마다 고정된 알림 ID |
| **alert_version** | 없음 | INT, NOT NULL, CHECK > 0 | **추가.** 순서가 뒤바뀌거나 다시 들어온 이벤트를 걸러냄 |
| dispatch_id | UUID, **FK**, NOT NULL | **VARCHAR(128)**, NOT NULL, **FK 없음**, 인덱스 | 이유는 dispatch_route와 같음 |
| target_vehicle_id | **UUID**, NOT NULL | **VARCHAR(128)**, NOT NULL | SUMO 차량 ID(`EVACS_CAR_000000`)가 UUID 형식이 아님 |
| selection_reason | VARCHAR, NOT NULL | VARCHAR(64), NOT NULL | 길이 지정 |
| estimated_arrival_seconds | INT, NULL | INT, NULL | 같음 |
| alert_status | **ENUM**, NOT NULL | **VARCHAR(16) + CHECK**, NOT NULL | 허용 값은 설계서와 같음 (PENDING, SENT, ACKNOWLEDGED, CLEARED, EXPIRED, FAILED) |
| first_sent_at | TIMESTAMP, NULL | DATETIME(3), NULL | 타입만 바뀜 |
| acknowledged_at | TIMESTAMP, NULL | DATETIME(3), NULL | 타입만 바뀜 |
| cleared_at | TIMESTAMP, NULL | DATETIME(3), NULL | 타입만 바뀜 |
| expires_at | TIMESTAMP, NULL | DATETIME(3), NULL | 타입만 바뀜 |
| **clear_reason** | 없음 | VARCHAR(64), NULL | **추가.** 알림이 해제된 원인 |

- 행 단위가 달라졌다. 설계서는 "발송 1건당 1행"이고, 현재는 "발송부터 해제까지 1행"이다. 해제 후 같은 `alert_id`로 다시 발송되면 새 행(새 `alert_log_id`)이 생긴다.

---

## 설계서에는 있지만 만들지 않는 테이블

- **출동 정보(dispatch) 테이블**(`dispatch_id` PK): 워커 마이그레이션에 없다. 출동 ID로는 API Server `emergency.id`(CHAR(36))를 그대로 쓴다.

## 남은 정리 사항

- `dispatch_id` 길이가 테이블마다 다르다(`dispatch_route` VARCHAR(64), `alert_delivery_log` VARCHAR(128)). 실제 값은 `emergency.id`(CHAR(36))이므로 하나로 맞추는 게 좋다. 워커 코드와 함께 바꿔야 해서 별도 마이그레이션으로 미뤄 뒀다.
- RDS 배포는 이제 Control API Server의 Flyway 한 곳에서 끝난다(`V3__worker_tables.sql`). 워커 레포의 `001_*.sql`은 적용하지 않는다 — 같은 내용의 기록으로만 남겨 둔다.
- 이미 워커 스크립트로 테이블을 만들어 둔 DB에서도 V3가 올라가도록 `CREATE TABLE IF NOT EXISTS`로 썼다. 기존 테이블의 정의가 다를 경우 자동 보정하지 않으므로 별도 대조가 필요하다. 다만 Flyway 이력이 없는 비어 있지 않은 스키마는 Flyway가 거부하므로, 그런 DB에는 `flyway baseline`이 필요하다.
