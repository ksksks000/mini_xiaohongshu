package com.hmdp.service.impl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;

@Service
public class UVService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String UV_KEY_PREFIX = "uv:stat:";

    /**
     * 记录用户访问
     * @param userId 用户ID
     */
    public void recordUV(Long userId) {
        if (userId == null) {
            return; // 未登录用户不统计
        }
        String key = UV_KEY_PREFIX + LocalDate.now();
        stringRedisTemplate.opsForHyperLogLog().add(key, userId.toString());
    }

    /**
     * 获取今日访问人数
     */
    public Long getTodayUV() {
        String key = UV_KEY_PREFIX + LocalDate.now();
        return stringRedisTemplate.opsForHyperLogLog().size(key);
    }
}