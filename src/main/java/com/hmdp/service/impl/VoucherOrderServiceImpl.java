package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import org.springframework.aop.framework.AopContext;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {

        //1.查询秒杀券id
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀券时间是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //3.没开始，返回异常结果
            return Result.fail("秒杀尚未开始");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.开始了，判断库存是否充足
        if(voucher.getStock()<1){
            //4.1不充足，返回异常结果
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //通过synchronized控制锁和用userId.toString().intern()锁对象的范围

        //这里避免用synchronized ，这是 JVM 层面的锁，这也正是它在集群环境下失效的根本原因
        /*synchronized(userId.toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);}*/

        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(5);
        //判断是否获取锁成功
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //关闭锁
            lock.unlock();
        }


    }

    //避免一人多单问题，那就要对用户id做检验
    //这里根据锁来控制id，锁什么？锁用户范围，只要那个池子里有，那就拿出来，对比他的数据
    //然后为了避免事务提交还未完成，重复刷单的问题，又得给事务加条件，确保事务执行完之后再释放锁
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();


            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否存在订单
            if (count > 0) {
                return Result.fail("用户已购买过");
            }
            //5.充足，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1")//set stock = stock - 1
                    .eq("voucher_id", voucherId)  //where voucher_id = ?
                    .gt("stock", 0) //and voucher_stock > 0
                    .update();
            //6.若库存扣减失败，返回异常结果
            if (!success) {
                return Result.fail("库存不足");
            }

            //7.库存扣减成功，创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1填入订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2填入用户id
            voucherOrder.setUserId(userId);
            //7.3填入秒杀券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //8.返回订单
            return Result.ok(orderId);

    }
}
