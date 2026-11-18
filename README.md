# EVACS Control API Server

`상세설계서수정본_윰씨윰씨.pdf`(관제서버 상세설계, 41~51p)의 Spring Boot 구현.
관제 대시보드의 차량 조회 / 긴급상황 조회·생성·종료를 담당하고, 상태 변경 이벤트를
Outbox를 거쳐 메시지 브로커로 발행한다. 발행된 이벤트는 Emergency Worker가 소비한다.

## 스택

- Spring Boot 3.5 (Java 17) / Spring MVC
- MySQL 8.4 + Spring Data JPA + Flyway (긴급상황 영속 데이터)
- Redis 7 (실시간 차량 위치·cone·알림 수신 상태, 이벤트 스트림)
- Uber H3 4.5 (차량 밀도 집계, 경로 지점 셀 계산)

## 구조 (설계서 클래스 1:1)

```
src/main/java/com/evacs/control/
  common/
    config/    EvacsProperties(evacs.* 설정 한 곳), RedisConfig(StringRedisTemplate + ObjectMapper)
    error/     ApiException / ApiError / GlobalExceptionHandler
    geo/       H3Support                      H3 셀 계산 래퍼(싱글턴)
  vehicle/                                    ── 실시간 조회(Redis)
    controller/ VehicleController             getVehicles/getVehicle/getSummary/getDensity
    service/    VehicleQueryService           findVehicles/findVehicle/getSummary/findDensity
    repository/ RedisVehicleRepository        findVehicles/findById/countByType
    domain/     VehicleState, VehicleCountSummary, VehicleType
    dto/        VehicleQueryDto, DensityQueryDto, *Response
  emergency/                                  ── 긴급상황(MySQL + Redis)
    controller/ EmergencyController           조회 6종 + 생성/종료
    service/    EmergencyQueryService         findEmergencies/findEmergency/findRoute/findEvents/findCone/findAlertedVehicles
                EmergencyCommandService       createEmergency/endEmergency (+ Outbox 적재)
    repository/ EmergencyRepository           findAll/findById/findActiveByVehicleId/save/updateStatus
                EmergencyRouteRepository      findByEmergencyId/save
                EventLogRepository            findByEmergencyId
                RedisConeRepository           findByEmergencyId
                RedisAlertRepository          findRecievedVehicleId
                jpa/                          Spring Data 어댑터(위 클래스들이 감싼다)
    domain/     Emergency, EmergencyRoute, EventLog, EmergencyStatus, ConeSnapshot
    dto/        EmergencyQueryDto, CreateEmergencyDto, *Response
  outbox/                                     ── 이벤트 발행
    processor/  OutboxProcessor               processPending/publishEvent (@Scheduled)
    publisher/  EventPublisher(interface), BrokerEventPublisher(Redis Streams XADD)
    repository/ OutboxRepository              save/claimPending/markPublished/markFailed
    domain/     OutboxEvent, OutboxStatus, EmergencyEventType
```

의존 방향은 `controller → service → repository` 한 방향이고, 명령(Command)은 저장소와
Outbox만 건드린다. 브로커 발행은 OutboxProcessor가 비동기로 하며 서비스는 모른다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/control/vehicles` | 차량 목록. `minLat/maxLat/minLng/maxLng`, `vehicleType`, `includeStale` |
| GET | `/control/vehicles/summary` | 전체/유형별 활성 차량 수 |
| GET | `/control/vehicles/density` | H3 셀별 밀도. `resolution`(기본 9), 영역·유형 필터 |
| GET | `/control/vehicles/{vehicleId}` | 차량 단건 |
| GET | `/control/emergencies` | 긴급상황 목록. `status`, `vehicleId`, `from`, `to`, `limit` |
| POST | `/control/emergencies` | 생성 → 201 (진행 중 건 있으면 409) |
| GET | `/control/emergencies/{id}` | 상세 |
| POST | `/control/emergencies/{id}/end` | 종료 (이미 종료면 409) |
| GET | `/control/emergencies/{id}/route` | 경로 지점 |
| GET | `/control/emergencies/{id}/events` | 이벤트 전송 이력 |
| GET | `/control/emergencies/{id}/cone` | Worker가 계산한 영향 H3 셀 |
| GET | `/control/emergencies/{id}/alerted-vehicles` | 알림 수신 확인 차량 |

실시간 차량 정보는 WebSocket이 아니라 **REST Polling**이다(설계서 2절). 밀어 주는 경로는
차량 클라이언트용(ws-server)이고, 관제 화면은 "지금 상태"를 주기적으로 당겨 간다.

## 다른 컴포넌트와의 계약 (Redis)

| 용도 | 키 | 쓰는 쪽 / 읽는 쪽 |
|---|---|---|
| 차량별 최신 위치 | `evacs:vehicle:location` (Hash) | ws-server가 `HSET` / 이 서버가 `HGETALL` |
| 긴급차량 영향 셀 | `evacs:emergency:{id}:cone` (String JSON) | Emergency Worker / 이 서버 |
| 알림 수신 차량 | `evacs:emergency:{id}:alerted` (Set) | Emergency Worker / 이 서버 |
| 긴급상황 이벤트 | `evacs:stream:emergency-event` (Stream) | 이 서버가 `XADD` / Emergency Worker가 소비 |

위치 스냅샷 해시에는 TTL이 없어 떠난 차량의 마지막 위치가 남는다. 그래서 `occurredAt`이
`evacs.vehicle.stale-after-seconds`(기본 30초)보다 오래된 항목은 활성 차량에서 뺀다
(요약의 `stale` 필드로 몇 건이 그런 잔재인지 볼 수 있다).

**스냅샷 해시 키와 위치 스트림 키는 다른 키다.** ws-server는 위치 1건을 받을 때마다
`EVACS_LOCATION_STREAM`에 `XADD`하고 `EVACS_LOCATION_SNAPSHOT_KEY`(해시)에 `HSET`한다.
이 서버가 읽어야 하는 건 **해시**이므로 `evacs.redis.location-snapshot-key`에 스트림 키를 넣으면
`WRONGTYPE Operation against a key holding the wrong kind of value`로 조회가 전부 500이 된다.
두 레포의 `EVACS_LOCATION_SNAPSHOT_KEY` 기본값은 모두 `evacs:vehicle:location`이고,
ws-server 쪽에서 이 값을 바꿨다면 이 서버도 같은 값으로 맞춰야 한다
(ws-server `.env`가 `EVACS_LOCATION_STREAM`만 바꿔 놓은 경우가 실제로 있었다).

이벤트 스트림에 길이 기준 트림(`XADD MAXLEN`)을 걸지 않았다. 소비자 그룹의 PEL/lag을 보지 않고
자르기 때문에 Worker가 아직 읽지 않은 이벤트가 조용히 사라진다. 보존이 필요하면 시간 기준
`XTRIM MINID`로 따로 돌려야 한다.

## 스키마 (MySQL)

영속 스키마는 `src/main/resources/db/migration/V1__init.sql` 하나다. `ddl-auto: validate`이므로
컬럼 타입은 Hibernate가 엔티티로부터 기대하는 타입과 **정확히** 같아야 한다.

| 자바 | 컬럼 | 비고 |
|---|---|---|
| `UUID` | `CHAR(36)` | `@JdbcTypeCode(SqlTypes.CHAR)`. 기본값 `BINARY(16)`은 SQL 콘솔에서 읽기 어렵다 |
| `Instant` | `DATETIME(6)` | MySQL에 `TIMESTAMPTZ`가 없다. 서버 `--default-time-zone=+00:00` + JDBC `connectionTimeZone=UTC`로 UTC 고정 |
| enum | `VARCHAR(16)` + CHECK | `@JdbcTypeCode(SqlTypes.VARCHAR)`. 안 붙이면 Hibernate가 네이티브 `enum(...)`을 기대한다 |
| JSON payload | `JSON` | Postgres `jsonb` 대체 |
| 긴 문자열 | `TEXT` | `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`. 기본값은 `varchar(255)`라 Outbox payload가 잘린다 |

MySQL에는 부분 인덱스(`WHERE`)가 없어서 두 곳을 바꿨다.

- 차량당 진행 중 긴급상황 1건: `active_vehicle_id`(ACTIVE일 때만 `vehicle_id`, 그 외 NULL인
  STORED 생성 컬럼)에 유니크를 건다. 유니크 인덱스는 NULL을 중복으로 보지 않는다.
  위반은 `DataIntegrityViolationException` → 409로 변환된다.
- Outbox 선점 인덱스: `(status, created_at)` 전체 인덱스. 선두 컬럼이 status라 선점 조회는
  여전히 PENDING/PROCESSING 구간만 훑는다.

## Outbox

긴급상황 상태 변경과 이벤트 적재가 **한 트랜잭션**이다. 서비스가 브로커로 직접 쏘면
"DB는 커밋됐는데 발행 실패" 또는 그 반대가 생긴다.

- `claimPending()`: `FOR UPDATE SKIP LOCKED`로 선점 → `PROCESSING` + `attempts+1`, 독립 트랜잭션.
  발행 중 프로세스가 죽어 `PROCESSING`으로 남은 행은 60초 뒤 다시 선점된다.
- 발행은 트랜잭션 **밖**에서 한다. 네트워크 I/O를 트랜잭션에 넣으면 브로커가 느릴 때
  DB 커넥션 풀이 먼저 마른다.
- 실패는 `PENDING`으로 되돌려 재시도하고, `evacs.outbox.max-attempts`를 넘기면 `FAILED`로 두고
  더 집지 않는다(이벤트 하나가 뒤를 전부 막는 상황 방지).
- **at-least-once**다. 발행 성공 직후 `markPublished` 전에 죽으면 같은 이벤트가 다시 나간다.
  소비자(Emergency Worker)가 `eventId`로 멱등 처리해야 한다.

## 실행

```sh
docker compose -f docker/compose.yml up -d --wait   # MySQL 33306, Redis 6402
./gradlew bootRun                                   # http://127.0.0.1:8081
```

스키마는 Flyway가 부팅 시 적용한다(`src/main/resources/db/migration`).
JPA는 `ddl-auto: validate`라 엔티티와 마이그레이션이 어긋나면 부팅이 실패한다 —
스키마 변경은 항상 새 마이그레이션 파일로 한다.

### 엔드포인트 테스터

서버를 띄운 뒤 <http://127.0.0.1:8081/api-tester.html> 를 연다. 정적 파일 한 장
(`src/main/resources/static/api-tester.html`)이라 빌드도 의존성도 없다.
엔드포인트별로 쿼리/경로 변수/JSON 본문을 채워 보내고 상태코드·소요시간·응답 본문만 본다.
같은 오리진에서 서빙되므로 CORS 설정이 필요 없다(Base URL을 비워 두면 된다).
`POST /control/emergencies`나 목록 조회 응답에서 본 긴급상황 id는 기억해 두었다가
`{id}` 칸이 비어 있으면 자동으로 채워 준다.

ws-server와 **실시간 경로를 연동해 볼 때는 같은 Redis를 봐야 한다.** 이 레포의 Redis(6402)
대신 `REDIS_PORT=6401`(evacs-ws-server의 compose)로 띄운다.

### 이미지로 실행

루트 `Dockerfile`은 멀티스테이지(gradle wrapper로 bootJar → JRE 런타임)다.
빌드 컨텍스트는 **레포 루트**이고, 이미지 빌드 중에는 테스트를 돌리지 않는다(PG·Redis가 필요).

```sh
docker build -t evacs-api-server:local .
docker compose -f docker/compose.yml up -d --wait
docker run --rm -p 8081:8081 \
  -e DB_HOST=host.docker.internal -e DB_PORT=33306 \
  -e REDIS_HOST=host.docker.internal -e REDIS_PORT=6402 \
  evacs-api-server:local
```

컨테이너 안에서 `127.0.0.1`은 컨테이너 자신이다 — 호스트 compose의 PG·Redis를 쓰려면
위처럼 `host.docker.internal`로 넘겨야 한다. `JAVA_OPTS`로 JVM 옵션을 덮을 수 있고
HEALTHCHECK는 `/actuator/health`를 본다.

### compose로 앱까지 (호스트 네트워크)

```sh
docker compose -f docker/compose.yml -f docker/compose.app.yml up -d --build
curl http://127.0.0.1:8081/actuator/health
docker compose -f docker/compose.yml -f docker/compose.app.yml down
```

`compose.app.yml`의 앱은 `network_mode: host`라 호스트의 `${PORT}`(기본 8081)를 그대로 점유하고
(`ports:` 매핑 없음), 의존 서비스는 compose.yml이 퍼블리시한 `127.0.0.1:33306`/`6402`로 붙는다.
macOS·Windows Docker Desktop은 host 네트워킹이 옵션이다 — Settings → Resources → Network의
"Enable host networking"이 꺼져 있으면 bridge로 떨어지므로 위 `docker run` 방식
(`host.docker.internal`)을 쓴다.

## 환경 변수

| 이름 | 기본값 |
|---|---|
| `PORT` | `8081` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `33306` / `evacs` |
| `DB_SSL_MODE` | `DISABLED` (운영에서는 `VERIFY_IDENTITY`) |
| `DB_USERNAME` / `DB_PASSWORD` | `evacs` / `evacs` |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6402` |
| `EVACS_LOCATION_SNAPSHOT_KEY` | `evacs:vehicle:location` |
| `EVACS_CONE_KEY_PATTERN` | `evacs:emergency:%s:cone` |
| `EVACS_ALERT_KEY_PATTERN` | `evacs:emergency:%s:alerted` |
| `EVACS_EMERGENCY_STREAM` | `evacs:stream:emergency-event` |
| `EVACS_VEHICLE_STALE_AFTER_SECONDS` | `30` |
| `EVACS_DENSITY_RESOLUTION` | `9` |
| `EVACS_OUTBOX_ENABLED` / `_POLL_INTERVAL_MS` / `_BATCH_SIZE` / `_MAX_ATTEMPTS` | `true` / `1000` / `100` / `5` |

읽기와 기본값은 `application.yml`과 `EvacsProperties` 두 곳에만 있다. 애플리케이션 코드는
`System.getenv`를 보지 않는다.

## 테스트

```sh
docker compose -f docker/compose.yml up -d --wait
./gradlew test
```

- 단위: 밀도 집계·좌표 검증(`VehicleQueryServiceTest`), ws-server JSON 파싱 계약(`VehicleStateParsingTest`)
- 통합(`ControlApiIntegrationTest`, 실제 PG·Redis): 스냅샷 조회/stale 제외/유형·영역 필터,
  생성 201 + 경로 저장 + **브로커 스트림 실제 발행**, 중복 생성 409, 종료 전이와 재종료 409,
  cone 미계산 시 `available=false`, Worker가 쓴 cone/알림 수신 읽기, 잘못된 ID 400/404

테스트 프로파일(`application-test.yml`)은 키에 `test:` 접두사를 붙여 개발 데이터와 분리한다.
파일명을 `application.yml`로 두면 main 설정을 **가려서** `evacs.*` 기본값이 통째로 사라진다.

## 미구현 (설계서 범위 밖)

인증/인가, Emergency Worker 자체(cone 계산·알림 전파·event_log 쓰기), 수평 확장 시 스케줄러
중복 실행 제어(현재는 Outbox 선점이 막아 주지만 리더 선출은 없음), `event_log` 쓰기 경로.
