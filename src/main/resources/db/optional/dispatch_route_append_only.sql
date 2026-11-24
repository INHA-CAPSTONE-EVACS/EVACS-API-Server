-- dispatch_route append-only 강제 (선택 적용, Flyway 대상이 아니다)
--
-- Flyway는 classpath:db/migration만 읽는다. 이 파일은 db/optional이라 자동 적용되지 않는다.
-- 트리거 생성은 바이너리 로깅이 켜진 MySQL에서 SUPER를 요구하므로(ERROR 1419) 마이그레이션에
-- 넣으면 RDS에서 앱이 부팅조차 못 한다. 권한이 있는 환경에서만 손으로 적용한다.
--
-- 로컬 compose MySQL:
--   docker exec -i evacs_api_db mysql -uevacs -pevacs evacs < src/main/resources/db/optional/dispatch_route_append_only.sql
--
-- RDS에서 꼭 걸고 싶다면 커스텀 파라미터 그룹에 log_bin_trust_function_creators = 1을 두고
-- 적용한다. DB 설정 변경은 운영 정책에 따라 관리자가 검토한다.
-- 걸지 않아도 실제 쓰기 경로는 Emergency Worker의 INSERT 하나뿐이고,
-- UNIQUE (dispatch_id, route_version)이 같은 버전의 재저장을 막는다.

DROP TRIGGER IF EXISTS dispatch_route_no_update;
CREATE TRIGGER dispatch_route_no_update BEFORE UPDATE ON dispatch_route FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'dispatch_route is append-only';

DROP TRIGGER IF EXISTS dispatch_route_no_delete;
CREATE TRIGGER dispatch_route_no_delete BEFORE DELETE ON dispatch_route FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'dispatch_route is append-only';
