package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 根据id从缓存快速查询店铺信息
     * @param id
     * @return
     */
    @Override
    public Result quickById(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //数据存在
        //你这里记得查redis的数据的时候，不能直接用null判断就行了
        //避免你缓存穿透
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //数据不存在
        //从数据库中查询数据
        Shop shop = getById(id);
        if (shop == null){
            //数据库中不存在该店铺数据
            return Result.fail("店铺数据不存在");
        }

        //数据库中存在该店铺数据
        //将该店铺数据存入redis缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        //返回店铺数据
        return Result.ok(shop);
    }
}
