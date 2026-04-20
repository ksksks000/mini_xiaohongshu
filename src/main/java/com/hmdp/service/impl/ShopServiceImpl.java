package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.core.PriorityOrdered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 根据id从缓存快速查询店铺信息
     * @param id
     * @return
     */
    @Override
    public Result quickById(Long id) {

        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null){
            return Result.fail("店铺 不存在");
        }
        return Result.ok(shop);

    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //缓存数据不存在，未命中
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        //命中缓存数据，把缓存数据序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //1.判断缓存数据是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //1.1缓存未过期，直接返回缓存
            return shop;
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
                    //1.2.3.2 写入缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //1.2.3.3 释放锁
                    unlock(lockKey);
                }


            });
        }
        //1.2.2未成功，返回旧店铺数据
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);


        //数据存在
        //你这里记得查redis的数据的时候，不能直接用null判断就行了
        //避免你缓存穿透
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }


        //判断是否要查的是空值
        //因为上面的isnotBlank过了之后只存在两种可能，null或者为空值
        //并且呢，这个顺序为什么排在后面？
        //因为，你有数据你就不用判断是否为空了呗
        if (shopJson != null){
            return null;
        }
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            //1,未命中，尝试获取互斥锁
            boolean islock = tryLock(lockKey);
            //2.判断是否获取到互斥锁
            if (!islock){
                //3.未获取到，休眠并重试
                Thread.sleep(50);
                //你看这里的if就直接把后面的堵住了，
                // 只有说真的拿到了互斥锁，才会放行下去
                //或者说，其他线程重建了，然后上面查缓存查到了就万事大吉
                return queryWithMutex(id);
            }


            //4.获取到互斥锁，

            //数据不存在
            //从数据库中查询数据，给重建提供id
            shop = getById(id);

            //模拟重建的延时
            //其实到catch之前，都可以看成是重建的过程，中间的一个  if (shop == null) 可以看成一个检验的东西
            Thread.sleep(200);


            if (shop == null){

                //为了避免缓存穿透，所以给缓存里设置空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

                //数据库中不存在该店铺数据
                return null;
            }

            //数据库中存在该数据
            //将该重建数据存入redis缓存中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.释放互斥锁
            unlock(lockKey);
        }

        //返回店铺数据
        return shop;
    }

    /**
     * 封装缓存穿透代码
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);


        //数据存在
        //你这里记得查redis的数据的时候，不能直接用null判断就行了
        //避免你缓存穿透
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断是否要查的是空值
        //因为上面的isnotBlank过了之后只存在两种可能，null或者为空值
        //并且呢，这个顺序为什么排在后面？
        //因为，你有数据你就不用判断是否为空了呗
        if (shopJson != null){
            return null;
        }
        //数据不存在
        //从数据库中查询数据
        Shop shop = getById(id);
        if (shop == null){

            //为了避免缓存穿透，所以给缓存里设置空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            //数据库中不存在该店铺数据
            return null;
        }

        //数据库中存在该店铺数据
        //将该店铺数据存入redis缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回店铺数据
        return shop;
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

    /**
     * 数据预热，添加热点数据
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //根据id拿到店铺数据
        Shop shop = getById(id);
        Thread.sleep(50);
        //把店铺数据和过期时间丢到RedisData对象里
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //把RedisData对象扔到缓存里

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 修改店铺信息，并且删除缓存
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {

        //判断是否存在该店铺id，没有就直接返回错误
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空或不存在该店铺可被修改");
        }
        //如果有，修改数据库中店铺信息
        updateById(shop);
        //修改数据库中店铺信息之后，删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
