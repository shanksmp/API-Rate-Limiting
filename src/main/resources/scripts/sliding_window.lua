-- Sliding window counter (weighted current + previous fixed window).
-- Approximates a true sliding log in O(1) memory per client.
--
-- KEYS[1]  window hash, e.g. rl:{key_abc}:sw:<plan>   fields: w (window start), c (current count), p (previous count)
-- ARGV[1]  limit       (max requests per window)
-- ARGV[2]  window_ms
-- ARGV[3]  cost
--
-- Returns  {allowed (0|1), remaining (floor), retry_after_ms, reset_ms}

local key    = KEYS[1]
local limit  = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost   = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cw  = now - (now % window)

local state = redis.call('HMGET', key, 'w', 'c', 'p')
local w = tonumber(state[1])
local c = tonumber(state[2]) or 0
local p = tonumber(state[3]) or 0

if w == nil then
  w = cw; c = 0; p = 0
elseif w ~= cw then
  if cw - w == window then p = c else p = 0 end
  c = 0
  w = cw
end

local into     = now - cw
local frac     = into / window
local weighted = p * (1 - frac) + c

local allowed  = 0
local retry_ms = 0

if weighted + cost <= limit then
  c = c + cost
  weighted = weighted + cost
  allowed = 1
else
  local room = limit - c - cost
  if room >= 0 and p > 0 then
    -- Wait until the previous window's weight has decayed enough
    local need_frac = 1 - room / p
    retry_ms = math.ceil(need_frac * window - into)
  else
    -- Current window alone is full: wait for the next window, where c becomes the previous count
    local until_next = window - into
    local extra = 0
    if c > 0 and limit - cost < c then
      extra = math.ceil((1 - (limit - cost) / c) * window)
    end
    retry_ms = until_next + extra
  end
  if retry_ms < 1 then retry_ms = 1 end
end

local remaining = math.floor(limit - weighted)
if remaining < 0 then remaining = 0 end

redis.call('HSET', key, 'w', w, 'c', c, 'p', p)
redis.call('PEXPIRE', key, window * 2)

return {allowed, remaining, retry_ms, window - into}
