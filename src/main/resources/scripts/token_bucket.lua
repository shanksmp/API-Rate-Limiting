-- Token bucket with continuous (fractional) refill.
--
-- KEYS[1]  bucket hash, e.g. rl:{key_abc}:tb:<plan>   fields: tokens, ts
-- ARGV[1]  capacity          (max tokens / burst size)
-- ARGV[2]  refill rate       (tokens per millisecond, may be fractional)
-- ARGV[3]  cost              (tokens this request consumes)
--
-- Returns  {allowed (0|1), remaining (floor), retry_after_ms, reset_ms}
--
-- Time comes from Redis itself, so every app instance shares one clock.

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed  = 0
local retry_ms = 0
if tokens >= cost then
  tokens  = tokens - cost
  allowed = 1
else
  retry_ms = math.ceil((cost - tokens) / rate)
end

local reset_ms = math.ceil((capacity - tokens) / rate)

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
-- Expire once the bucket would be full again: an absent bucket is a full bucket
redis.call('PEXPIRE', key, reset_ms + 1000)

return {allowed, math.floor(tokens), retry_ms, reset_ms}
