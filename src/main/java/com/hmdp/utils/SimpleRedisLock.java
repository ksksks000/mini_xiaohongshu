package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 实现分布式锁
 */
public class SimpleRedisLock implements Ilock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    //这个工具类里面的UUID可以放个true进去，把UUID里面默认的横线去掉
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //静态代码块初始化脚本，随着类的加载而加载，不用每次调用锁都加载，提高性能
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        //创建
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //设置脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        //获取线程标识
        String  threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //避免自动拆箱过程
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unlock() {

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );

    }
   /* @Override
    public void unlock() {

        //获取线程标识（在jvm中运行的线程）
        String  threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中标识（在Redis里面运行的分布式锁）
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断线程标识和锁中标识是否一致
        if (threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX +name);
        }

    }*/
}
