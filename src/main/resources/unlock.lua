-- 比较线程标识与锁中标识
if(redis.call('get' , KEYS[1]) == ARGV[1]) then
    -- 释放锁 del key
    return redis.cal('del', KEYS[1] )
end
return 0
