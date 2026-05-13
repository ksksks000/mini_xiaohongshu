package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.core.PriorityOrdered;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id从缓存快速查询店铺信息
     *
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
        //Shop shop = queryWithLogicalExpire(id);

       /* Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY,id, Shop.class,
                        id2 ->getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);*/
        //逻辑过期解决缓存击穿
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                        id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺 不存在");
        }
        return Result.ok(shop);

    }


    /**
     * 互斥锁解决缓存击穿
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //根据id从redis缓存中直接查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);


        //数据存在
        //你这里记得查redis的数据的时候，不能直接用null判断就行了
        //避免你缓存穿透
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }


        //判断是否要查的是空值
        //因为上面的isnotBlank过了之后只存在两种可能，null或者为空值
        //并且呢，这个顺序为什么排在后面？
        //因为，你有数据你就不用判断是否为空了呗
        if (shopJson != null) {
            return null;
        }
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            //1,未命中，尝试获取互斥锁
            boolean islock = tryLock(lockKey);
            //2.判断是否获取到互斥锁
            if (!islock) {
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


            if (shop == null) {

                //为了避免缓存穿透，所以给缓存里设置空值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                //数据库中不存在该店铺数据
                return null;
            }

            //数据库中存在该数据
            //将该重建数据存入redis缓存中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
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
/*
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
*/

    /**
     * 创建锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 数据预热，添加热点数据
     * @param id
     * @param expireSeconds
     */
    /*public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //根据id拿到店铺数据
        Shop shop = getById(id);
        Thread.sleep(50);
        //把店铺数据和过期时间丢到RedisData对象里
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //把RedisData对象扔到缓存里

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }*/

    /**
     * 修改店铺信息，并且删除缓存
     *
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {

        //判断是否存在该店铺id，没有就直接返回错误
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空或不存在该店铺可被修改");
        }
        //如果有，修改数据库中店铺信息
        updateById(shop);
        //修改数据库中店铺信息之后，删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
            if (x == null || y == null) {
                // 如果坐标为空，则不需要根据距离查询，直接根据 type 分页查询数据库（这里简化处理，只写了 Redis 的逻辑）
                // 实际业务中，这里可能需要调用 page 方法查询 MySQL
                Page<Shop> page = query()
                        .eq("type_id", typeId)
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
                return Result.ok(page.getRecords());
            }

            // 2. 计算分页参数
            // 计算起始位置 (current - 1) * limit
            int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
            // 计算结束位置 (current * limit) - 1
            int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

            // 3. 查询 Redis、按照距离排序、分页。结果: shopId、distance
            // key 的格式要和之前存入时一致：shop:geo:typeId
            String key = SHOP_GEO_KEY + typeId;

            // 使用 RedisTemplate 执行 GEOSEARCH 命令 (Spring Data Redis 2.2+)
            // 如果版本较低，可以使用 geoRadius 方法
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                    .search(
                            key,
                            GeoReference.fromCoordinate(x, y), // 指定中心点坐标
                            new Distance(5000), // 搜索半径，比如 5km，这里其实可以设大一点以保证分页够用，或者不设半径直接查
                            RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                                   /* .includeDistance() // 包含距离
                                    .limit(end + 1)    // 限制查询数量，为了分页，我们需要查出前 end+1 条数据
                                    .sortAscending()   // 按距离升序*/
                    );

            if (results == null ) {
                return Result.ok(Collections.emptyList());
            }
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageContent = results.getContent();
            // 4. 解析出 id
            // 我们需要截取当前页的数据
            // 注意：如果查询结果总数小于 from，说明这一页没数据了
            if (pageContent.size() <= from){
                return Result.ok(Collections.emptyList());
            }
            List<Long> ids = new ArrayList<>(pageContent.size());
            // 用于存放距离信息，以便返回给前端（可选）
            Map<String, Distance> distanceMap = new HashMap<>(pageContent.size());

            // 截取分页数据 [from, end]
            // subList 的 toIndex 是 exclusive 的，所以要 Math.min 防止越界

            pageContent.stream().skip(from).forEach(
                    result -> {
                        String shopIdStr = result.getContent().getName();
                        ids.add(Long.valueOf(shopIdStr));
                        Distance distance = result.getDistance();
                        distanceMatonp.put(shopIdStr, distance);
                    }
            );

            // 5. 根据 id 查询 Shop
            // 为了保持 Redis 排序的顺序，我们需要按照 ids 的顺序返回
            // 使用 MySQL 的 FIELD 函数或者在 Java 中排序
            // 这里演示使用 MyBatis Plus 的 in 查询，然后在内存中排序（或者使用 SQL 的 order by field）
            String idStr = StrUtil.join(",",ids);
            List<Shop> shopList = query().in("id", ids)
                    .last("ORDER BY FIELD(id," + idStr + ")") // 保持数据库查询顺序和 Redis 一致
                    .list();

            for (Shop shop : shopList) {
                shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
            }
            // 6. 返回
            // 如果需要把距离也返回给前端，可以在这里封装到 DTO 中
            return Result.ok(shopList);
        }
    }

