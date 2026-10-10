-- One location update for one driver (LLD §9.4, §9.5).
-- KEYS: 1 {city}:drv:<id>  2 {city}:seen  3 {city}:geo:<category>  4 {city}:geo:online
-- ARGV: 1 id  2 seq  3 now (ms)  4 lat  5 lon  6 accuracy (m)  7 heading or ''  8 speed or ''  9 expected category
--       10 max accuracy (m)  11 max speed (m/s)  12 the implausible update, counted in a row, taken as the position
-- Returns {code, status, category, ride, flags}: code 1 applied, 0 stale, -1 offline, -2 category mismatch, which
-- alone returns the mirror's category; flags 1 poor accuracy, 2 implausible.

-- Non-negative integers as decimal strings, compared exactly: Lua numbers are doubles.
local function newer(a, b)
  if #a ~= #b then return #a > #b end
  return a > b
end

local s = redis.call('HMGET', KEYS[1], 'status', 'seq', 'cat', 'ride', 'plat', 'plon', 'pts', 'bad')
if not s[1] or s[1] == 'OFFLINE' then return {-1, 'OFFLINE', '', '', 0} end
if s[3] ~= ARGV[9] then return {-2, s[1], s[3] or '', s[4] or '', 0} end
if s[2] and not newer(ARGV[2], s[2]) then return {0, s[1], '', s[4] or '', 0} end
local now, lat, lon = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])
redis.call('HSET', KEYS[1], 'seq', ARGV[2], 'ts', ARGV[3])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
local flags = 0
if tonumber(ARGV[6]) > tonumber(ARGV[10]) then
  flags = 1
elseif s[5] then
  local la1, lo1 = math.rad(tonumber(s[5])), math.rad(tonumber(s[6]))
  local la2, lo2 = math.rad(lat), math.rad(lon)
  local a = math.sin((la2 - la1) / 2) ^ 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ^ 2
  local metres = 2 * 6371008.8 * math.asin(math.min(1, math.sqrt(a)))
  local seconds = math.max((now - tonumber(s[7])) / 1000, 1)
  local bad = (tonumber(s[8]) or 0) + 1
  if metres / seconds > tonumber(ARGV[11]) and bad < tonumber(ARGV[12]) then
    redis.call('HSET', KEYS[1], 'bad', bad)
    flags = 2
  end
end
if flags == 0 then
  redis.call('HSET', KEYS[1], 'plat', ARGV[4], 'plon', ARGV[5], 'pts', ARGV[3], 'acc', ARGV[6], 'hdg', ARGV[7],
             'spd', ARGV[8], 'bad', 0)
  redis.call('GEOADD', KEYS[4], ARGV[5], ARGV[4], ARGV[1])
  if s[1] == 'AVAILABLE' then redis.call('GEOADD', KEYS[3], ARGV[5], ARGV[4], ARGV[1]) end
end
return {1, s[1], '', s[4] or '', flags}
