-- Location Worker가 정제해 둔 '현재 차량 상태'를 한 번의 왕복으로 모두 읽는다.
--
-- 워커 계약(Location-Worker design.md §2):
--   h3:cells:active        SET   비어 있지 않은 셀 ID
--   h3:vehicles:{cellId}   ZSET  member = vehicleId, score = 수신 시각 ms
--   vehicle:{id}:state     Hash  좌표·속도·방향·h3CellId·status 등 (갱신마다 EXPIRE 3600s)
--
-- 셀 ZSET 키와 상태 Hash 키는 h3:cells:active의 내용을 보고 나서야 알 수 있으므로
-- KEYS로 선언할 수 없다. 워커의 apply-location.lua와 같은 제약이고, 단일 Redis에서만
-- 유효하다(Cluster 불가).
--
-- KEYS[1] = h3:cells:active
-- ARGV[1] = 셀 ZSET 키 접두사      (예: 'h3:vehicles:')
-- ARGV[2] = 상태 Hash 키 접두사    (예: 'vehicle:')
-- ARGV[3] = 상태 Hash 키 접미사    (예: ':state')
--
-- 반환: 차량별 HGETALL 결과 배열의 배열. 신선도·좌표·유형 필터는 호출자가 적용한다.
-- 빈 Hash(= TTL로 만료됐는데 ZSET 멤버가 아직 남은 차량)는 건너뛴다.

local cells = redis.call('SMEMBERS', KEYS[1])
local result = {}
local seen = {}

for i = 1, #cells do
  local members = redis.call('ZRANGE', ARGV[1] .. cells[i], 0, -1)
  for j = 1, #members do
    local vehicleId = members[j]
    -- 셀 이동과 정리 사이의 짧은 구간에서 같은 차량이 두 셀에 보일 수 있다.
    if not seen[vehicleId] then
      seen[vehicleId] = true
      local state = redis.call('HGETALL', ARGV[2] .. vehicleId .. ARGV[3])
      if #state > 0 then
        result[#result + 1] = state
      end
    end
  end
end

return result
