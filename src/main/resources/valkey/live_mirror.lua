-- Dispatch's state for one driver, applied only if its version is newer than the one held (LLD §9.4), so writes
-- made after commit may arrive in any order.
-- KEYS: 1 {city}:drv:<id>  2 {city}:seen  3 {city}:geo:online  4 {city}:drivers  5 {city}:cats
--       6 {city}:geo:<the category the caller read from the hash>  7 {city}:geo:<the state's category>
-- ARGV: 1 id  2 status  3 version  4 category or ''  5 ride or ''  6 now (ms)  7 tombstone expiry (ms)
--       8 tombstone lifetime (ms)  9 the category the caller read, or ''
-- Returns 1 applied, 0 not newer than what the index holds, -1 the hash's category isn't the one the caller read:
-- read it again and retry.

-- Non-negative integers as decimal strings, compared exactly: Lua numbers are doubles.
local function newer(a, b)
  if #a ~= #b then return #a > #b end
  return a > b
end

local cur = redis.call('HMGET', KEYS[1], 'sv', 'cat', 'exp', 'plat', 'plon')
-- A tombstone past its expiry counts as no entry; its key's TTL only frees the memory.
local held = cur[1] and not (cur[3] and tonumber(cur[3]) <= tonumber(ARGV[6]))
if held and not newer(ARGV[3], cur[1]) then return 0 end
if ((held and cur[2]) or '') ~= ARGV[9] then return -1 end
redis.call('SADD', KEYS[4], ARGV[1])
if ARGV[2] == 'OFFLINE' then
  -- The tombstone keeps only the version, so a new device's sequence numbers count again.
  redis.call('DEL', KEYS[1])
  redis.call('HSET', KEYS[1], 'status', 'OFFLINE', 'sv', ARGV[3], 'exp', ARGV[7])
  redis.call('PEXPIRE', KEYS[1], ARGV[8])
  redis.call('ZREM', KEYS[2], ARGV[1])
  redis.call('ZREM', KEYS[3], ARGV[1])
  redis.call('ZREM', KEYS[6], ARGV[1])
  return 1
end
redis.call('SADD', KEYS[5], ARGV[4])
-- The category changed without an offline write in between (one was missed): leave the old category's matching.
if KEYS[6] ~= KEYS[7] then redis.call('ZREM', KEYS[6], ARGV[1]) end
-- After a tombstone, or nothing, there is no position or sequence to keep: a tombstone has neither.
redis.call('HSET', KEYS[1], 'status', ARGV[2], 'sv', ARGV[3], 'cat', ARGV[4], 'ride', ARGV[5])
redis.call('HDEL', KEYS[1], 'exp')
redis.call('PERSIST', KEYS[1])
if ARGV[2] == 'AVAILABLE' and cur[4] then
  redis.call('GEOADD', KEYS[7], cur[5], cur[4], ARGV[1])
else
  redis.call('ZREM', KEYS[7], ARGV[1])
end
return 1
