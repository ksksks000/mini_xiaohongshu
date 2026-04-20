package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1776643200;

    private static final int COUNT_BITS  = 32 ;
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 全局ID生成器
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();

        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond- BEGIN_TIMESTAMP;
        //生成序列号
        //1。获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2。自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + date);
        //拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    /**
     * 生成当前时间秒数
     * @param args
     */
    /*public static void main(String[] args){
        LocalDateTime time = LocalDateTime.of(2026,4,20,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }*/

}
