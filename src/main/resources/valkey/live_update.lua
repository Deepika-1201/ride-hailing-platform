-- One location update for one driver (LLD §9.4). Phase 13 applies the sequence and status rules; the quality rules
-- arrive in phase 14, in both implementations.
-- KEYS: 1 {city}:drv:<id>  2 {city}:seen  3 {city}:geo:<category>  4 {city}:geo:online
-- ARGV: 1 id  2 seq  3 now (ms)  4 lat  5 lon  6 accuracy (m)  7 heading or ''  8 speed or ''  9 expected category
-- Returns {code, status, category, ride}: code 1 applied, 0 stale, -1 offline, -2 category mismatch, which alone
-- returns the mirror's category.

-- Non-negative integers as decimal strings, compared exactly: Lua numbers are doubles.
local function newer(a, b)
  if #a ~= #b then return #a > #b end
  return a > b
end

local s = redis.call('HMGET', KEYS[1], 'status', 'seq', 'cat', 'ride')
if not s[1] or s[1] == 'OFFLINE' then return {-1, 'OFFLINE', '', ''} end
if s[3] ~= ARGV[9] then return {-2, s[1], s[3] or '', s[4] or ''} end
if s[2] and not newer(ARGV[2], s[2]) then return {0, s[1], '', s[4] or ''} end
redis.call('HSET', KEYS[1], 'seq', ARGV[2], 'ts', ARGV[3], 'plat', ARGV[4], 'plon', ARGV[5], 'pts', ARGV[3],
           'acc', ARGV[6], 'hdg', ARGV[7], 'spd', ARGV[8])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
redis.call('GEOADD', KEYS[4], ARGV[5], ARGV[4], ARGV[1])
if s[1] == 'AVAILABLE' then redis.call('GEOADD', KEYS[3], ARGV[5], ARGV[4], ARGV[1]) end
return {1, s[1], '', s[4] or ''}
