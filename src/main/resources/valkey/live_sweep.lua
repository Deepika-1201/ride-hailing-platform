-- Drivers last heard from in the minute before the cutoff leave matching (LLD §9.4). Earlier silences were swept
-- already, and ZREM is idempotent.
-- KEYS: 1 {city}:seen  2..n {city}:geo:<category> for every category in {city}:cats
-- ARGV: 1 silence cutoff (ms, exclusive)  2 window start (ms, inclusive)
-- Returns the drivers swept.
local silent = redis.call('ZRANGE', KEYS[1], ARGV[2], '(' .. ARGV[1], 'BYSCORE', 'LIMIT', 0, 5000)
if #silent > 0 then
  for i = 2, #KEYS do redis.call('ZREM', KEYS[i], unpack(silent)) end
end
return silent
