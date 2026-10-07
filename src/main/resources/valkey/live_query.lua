-- Available drivers of a category near a point, heard from recently, nearest first (LLD §9.4). The caller reads
-- their exact positions and measures the distances: GEOSEARCH's coordinates are geohash approximations.
-- KEYS: 1 {city}:geo:<category>  2 {city}:seen
-- ARGV: 1 lon  2 lat  3 radius (m)  4 count  5 oldest acceptable last-seen (ms)
-- Returns id, last seen (ms), id, last seen, ...
local found = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2], 'BYRADIUS', ARGV[3], 'm',
                         'ASC', 'COUNT', ARGV[4])
local out, oldest = {}, tonumber(ARGV[5])
for _, id in ipairs(found) do
  local seen = redis.call('ZSCORE', KEYS[2], id)
  if seen and tonumber(seen) >= oldest then
    out[#out + 1] = id
    out[#out + 1] = seen
  end
end
return out
