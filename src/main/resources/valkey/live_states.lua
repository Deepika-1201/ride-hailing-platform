-- What the index holds for some drivers (LLD §9.4): for the reconciler and the operations snapshot.
-- KEYS: 1 {city}:drivers  2..n {city}:drv:<id>
-- ARGV: 1 now (ms)  2 '1' to remove drivers whose hash is gone from {city}:drivers  3..n the ids of KEYS 2..n
-- Returns, for each driver held: id, status, version, category, ride, last seen (ms), lat, lon; '' where missing.
-- A tombstone past its expiry counts as nothing held.
local out, now = {}, tonumber(ARGV[1])
for i = 2, #KEYS do
  local id = ARGV[i + 1]
  local h = redis.call('HMGET', KEYS[i], 'status', 'sv', 'cat', 'ride', 'exp', 'ts', 'plat', 'plon')
  if not h[1] then
    if ARGV[2] == '1' then redis.call('SREM', KEYS[1], id) end
  elseif not (h[5] and tonumber(h[5]) <= now) then
    out[#out + 1] = id
    for _, j in ipairs({1, 2, 3, 4, 6, 7, 8}) do out[#out + 1] = h[j] or '' end
  end
end
return out
