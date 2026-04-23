-- 锁的key
--local key = KEY[1]
--当前线程标识
--local threadId = ARGV[1]


--获取锁中的线程标识，get，key
local id = redis.call('key', KEY[1])
--比较当前线程标识和锁中的线程标识是否相同
if(id == ARGV[1]) then
    --相同，释放锁，del，key
    return redis.call('del', KEY[1])
end
--不一致
return 0;