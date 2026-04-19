package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.core.PriorityOrdered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
        Shop shop = queryWithMutex(id);

        if (shop == null){
            return Result.fail("店铺 不存在");
        }
        return Result.ok(shop);

    }

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
                return queryWithMutex(id);
            }


            //4.获取到互斥锁，

            //数据不存在
            //从数据库中查询数据
            shop = getById(id);

            //模拟重建的延时
            Thread.sleep(200);


            if (shop == null){

                //为了避免缓存穿透，所以给缓存里设置空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

                //数据库中不存在该店铺数据
                return null;
            }

            //数据库中存在该店铺数据
            //将该店铺数据存入redis缓存中
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

    //封装缓存穿透代码
    //防止代码丢失
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

    //创建锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
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
