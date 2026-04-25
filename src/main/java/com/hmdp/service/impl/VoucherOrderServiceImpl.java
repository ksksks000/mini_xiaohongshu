package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    //创建阻塞队列，拿来装订单
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建一个线程(个数取决于你想要的快慢吧)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();




    /**
     * 用于在类一初始化的完毕时候就可以执行 阻塞队列
     */
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 执行阻塞队列
     */
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //1.获取队列里的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    //e.printStackTrace();
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id(因为目前无法从这个新的线程里的userholder拿到用户id了，里面压根没有)
        Long userId = voucherOrder.getUserId();

        //创建锁对象（没必要了）
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功（没必要了）
        if (!isLock){
            //获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return ;
        }
        try {
            //获取代理对象（事务）
            //IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            //为什么注释掉呢，因为你改用子线程去做后续操作，
            //作为一个子线程，是没有办法从thread local里去获取东西
            //只能说提前在主线程里面去获取，主线程就是你前面做秒杀那个流程

            //代理对象异步去创建订单（不需要返回值）（哝，保证调用的这个方法能够开启事务，内部同时成功或失败）
             proxy.createVoucherOrder(voucherOrder);
        } finally {
            //关闭锁
            lock.unlock();
        }


    }


    //静态代码块初始化lua脚本，随着类的加载而加载，不用每次调用锁都加载，提高性能
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        //创建
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //设置脚本位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置返回类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //这里把代理对象搞成成员变量，你主线程里获取完了，可以贡献出来给子线程去用
    private IVoucherOrderService proxy;

    /**
     * 这里是主线程（用来完成执行lua脚本、判断库存、判断重复、并且将用户信息存入阻塞队列）
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {

        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果是否为0
        int r = result.intValue();
        //2.1判断结果不为0
        if (r != 0){
            //2.2判断结果为0
            return Result.fail(r == 1 ?"库存不足":"不能重复下单");
        }
        //3.将优惠券id，用户id和订单id存入消息队列
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(orderId);
        //7.2填入用户id
        voucherOrder.setUserId(userId);
        //7.3填入秒杀券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //获取代理对象（开启事务，作用是，保证扣减库存和创建订单同时成功或失败，就是保障createVoucherOrder方法里内容一定执行成功）
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //
        //4.返回订单id
        return  Result.ok(orderId);
    }












    /* @Override
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
        *//*synchronized(userId.toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);}*//*

        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
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
*/
    //避免一人多单问题，那就要对用户id做检验
    //这里根据锁来控制id，锁什么？锁用户范围，只要那个池子里有，那就拿出来，对比他的数据
    //然后为了避免事务提交还未完成，重复刷单的问题，又得给事务加条件，确保事务执行完之后再释放锁
    @Transactional
    //public Result createVoucherOrder(VoucherOrder voucherOrder) {
    /**
     *
     * TODO 现在这里变成子线程（用来被调用创建订单、减数据库库存）
     *
     * （就是主线程存入阻塞队列之后 另外开的线程来完成后续操作的）
     * （所以，也是拿不到thread local的东西，也得是自己从voucherorder中拿用户id）
     * （因为，这个新的线程里，userholder里是空的）
     */
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();

            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
            //判断是否存在订单(没必要了)
            if (count > 0) {
                log.error("已经购买过了");
                return ;
            }
            //5.充足，扣减库存（这个是数据库层面的真正的减库存，前面的知识Redis缓存的减库存，还是有必要的）
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1")//set stock = stock - 1
                    .eq("voucher_id", voucherOrder)  //where voucher_id = ?
                    .gt("stock", 0) //and voucher_stock > 0
                    .update();
            //6.若库存扣减失败，返回异常结果(没必要了)
            if (!success) {
                log.error("库存不足");
                return ;
            }

/*            //7.库存扣减成功，创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1填入订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2填入用户id
            voucherOrder.setUserId(userId);
            //7.3填入秒杀券id
            voucherOrder.setVoucherId(voucherOrder);*/

            //保存订单
            save(voucherOrder);


            //8.返回订单
            //return Result.ok(orderId);

    }
}
