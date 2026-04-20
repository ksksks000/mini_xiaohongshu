package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 逻辑过期，解决缓存击穿
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /**
     * 缓存空值解决缓存穿透
     * @param id
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix,
                                         ID id,
                                         Class<R> type,
                                         Function<ID,R> dbFallback,
                                         Long time,
                                         TimeUnit unit){
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String json = stringRedisTemplate.opsForValue().get(key);


        //数据存在
        //你这里记得查redis的数据的时候，不能直接用null判断就行了
        //避免你缓存穿透
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }

        //判断是否要查的是空值
        //因为上面的isnotBlank过了之后只存在两种可能，null或者为空值
        //并且呢，这个顺序为什么排在后面？
        //因为，你有数据你就不用判断是否为空了呗
        if (json != null){
            return null;
        }
        //数据不存在
        //从数据库中查询数据
        R r = dbFallback.apply(id);
        if (r == null){

            //为了避免缓存穿透，所以给缓存里设置空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            //数据库中不存在该店铺数据
            return null;
        }

        //数据库中存在该店铺数据
        //将该店铺数据存入redis缓存中
        this.set(key,r,time,unit);
        //返回店铺数据
        return r;
    }




    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix ,
                                           ID id,
                                           Class<R> type,
                                           Function<ID,R> dbFallback,
                                           Long time,
                                           TimeUnit unit){
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String json = stringRedisTemplate.opsForValue().get(key);

        //缓存数据不存在，未命中
        if(StrUtil.isBlank(json)){
            return null;
        }

        //命中缓存数据，把缓存数据序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //1.判断缓存数据是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //1.1缓存未过期，直接返回缓存
            return r;
        }

        //1.2缓存过期，获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //1.2.1判断是否获取锁成功
        boolean isLock = tryLock(lockKey);
        if (isLock){
            //1.2.3 成功，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //1.2.3.1 根据id查询数据库
                    R apply = dbFallback.apply(id);

                    //1.2.3.2 写入缓存
                    this.setWithLogicalExpire(key,r,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //1.2.3.3 释放锁
                    unlock(lockKey);
                }


            });
        }
        //1.2.2未成功，返回旧店铺数据
        return r;
    }


    /**
     * 创建锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
