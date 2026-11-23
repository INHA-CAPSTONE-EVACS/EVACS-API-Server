-- SUMO 모드 플래그. true면 긴급상황 생성 직후 SUMO 시뮬레이터에 긴급차량을 투입한다.
-- Hibernate의 boolean 매핑과 맞추려면 BIT(1)이어야 한다(TINYINT로 두면 validate가 깨진다).
ALTER TABLE emergency
    ADD COLUMN is_sumo BIT(1) NOT NULL DEFAULT b'0';
