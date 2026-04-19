package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 从redis中查询店铺分类数据
     * @return
     */
    @Override
    public Result queryList() {
        // 1. 定义 Redis 的 Key
        String cacheKey = "cache:shop_type:list";

        // 2. 尝试从 Redis 获取缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(cacheKey);

        // 3. 判断缓存是否命中
        if (shopTypeJson != null) {
            // 命中缓存，将 JSON 字符串转回 List 对象（或者直接返回字符串给前端也可以）
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }

        // 4. 缓存未命中，查询数据库
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        // 5. 将数据库查询结果写入 Redis
        // 将 List 对象序列化为 JSON 字符串
        String jsonStr = JSONUtil.toJsonStr(typeList);
        // 设置过期时间，例如 30 分钟，防止数据不一致
        stringRedisTemplate.opsForValue().set(cacheKey, jsonStr, 30, TimeUnit.MINUTES);

        // 6. 返回结果
        return Result.ok(typeList);
    }
}
