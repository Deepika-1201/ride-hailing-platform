-- One request against a token bucket (LLD §5.8): capacity tokens, refilled continuously at capacity per period.
-- KEYS: 1 rl:<limit>:<key>
-- ARGV: 1 capacity  2 period (ms)  3 now (ms)
-- Returns {1, 0} when a token was taken, or {0, ms until one is available}.
local capacity, period, now = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local tokens, at = capacity, now
if bucket[1] then
  tokens, at = tonumber(bucket[1]), tonumber(bucket[2])
  if now > at then
    tokens = math.min(capacity, tokens + (now - at) * capacity / period)
    at = now
  end
end
local allowed = tokens >= 1
if allowed then tokens = tokens - 1 end
redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'at', string.format('%d', at))
-- Unused for a whole period, the bucket would be full again, so the key may go.
redis.call('PEXPIRE', KEYS[1], ARGV[2])
if allowed then return {1, 0} end
return {0, math.ceil((1 - tokens) * period / capacity)}
