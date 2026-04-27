package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    @Resource(name = "redissonClient") // 明确告诉 Spring 我要 1 号
    private RedissonClient redissonClient;

    //创建一个线程(个数取决于你想要的快慢吧)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();



    String queueName = "stream.orders";
    // 消费者组名称，通常与创建组时的名称一致
    String groupName = "g1";
    // 消费者名称，通常使用 IP+UUID 或简单的标识
    String consumerName = "c1";


    /**
     * 【核心新增】项目启动时初始化 Redis Stream Group
     * 这个注解保证了在 Spring 容器把这个 Bean 创建好之后，立刻执行这个方法
     */
    @PostConstruct
    private void initStreamGroup() {
        log.info("正在初始化 Redis Stream 消费者组: {} -> {}", queueName, groupName);
        try {
            // 尝试创建 Group
            stringRedisTemplate.opsForStream().createGroup(
                    queueName,
                    ReadOffset.latest(),
                    groupName
            );
            log.info("✅ Redis Stream 环境初始化成功: 消费者组 [{}] 已就绪", groupName);
        } catch (Exception e) {
            // 如果是消费者组已存在（BUSYGROUP），则忽略，继续启动
            if (e instanceof org.springframework.data.redis.RedisSystemException
                    && e.getCause() != null
                    && e.getCause().getMessage().contains("BUSYGROUP")) {
                log.warn("⚠️ 消费者组 [{}] 已存在，环境检查通过", groupName);
            } else {
                // 其他异常（如 Redis 连接失败、权限不足）导致启动失败
                log.error("❌ 致命错误: Redis Stream 初始化失败，请检查 Redis 配置", e);
                throw new RuntimeException("Redis Stream 初始化失败", e);
            }
        }
        // 启动消费线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //内部类

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    // 1. 获取 stream 消息队列里的订单信息
                    // 使用 XREADGROUP，监听新消息（ReadOffset.lastConsumed() 对应 ID '>'）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), // 阻塞2秒，避免空转太频繁
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 1.1 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果为空，说明暂时没有新消息，继续下一次循环（或者可以休眠一小会儿）
                        continue;
                    }

                    // 1.3 消息获取成功，可以下单
                    // 解析消息内容
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 将 Map 转换为订单对象 (假设你有一个 VoucherOrder 类)
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 创建订单 (执行业务逻辑)
                    handleVoucherOrder(voucherOrder);

                    // 1.4 ACK 确认
                    // 只有业务逻辑执行成功后，才向 Redis 发送 ACK，告知消息已处理，可以从 Pending List 移除
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

                } catch (Exception e) {
                    // 1.2 消息获取失败 (或业务处理异常)
                    log.error("处理订单异常", e);
                    // 注意：这里不执行 ACK，消息会留在 Pending List 中，
                    // 通常会有一个单独的线程或逻辑去处理 Pending List 中的失败消息（重试机制）
                    handlePendingList();
                }
            }
        }

        /**
         * 处理 Pending List 中的消息
         * 当主循环出现异常时，调用此方法确保消息不丢失，实现最终一致性
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取 pending-list 中的订单信息
                    // 关键点：使用 ReadOffset.from("0")
                    // 这对应 Redis 命令：XREADGROUP GROUP g1 c1 STREAMS stream.order 0
                    // "0" 表示读取当前消费者组下所有未 ACK 的消息（即 Pending List）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName ),
                            StreamReadOptions.empty().count(1), // 每次处理一条
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2. 判断 pending-list 是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为空，说明所有异常消息都已处理完毕，退出循环，回到主循环继续监听新消息
                        break;
                    }

                    // 3. 解析数据并重试
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 4. 再次尝试创建订单
                    handleVoucherOrder(voucherOrder);

                    // 5. 如果成功，进行 ACK 确认，将消息从 Pending List 中移除
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

                } catch (Exception e) {
                    log.error("处理 pending-list 订单异常", e);
                    // 如果处理 Pending List 中的消息依然失败，为了防止死循环占用过多 CPU，
                    // 可以休眠一小段时间（例如 20ms - 100ms），然后继续重试
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
    /**
     * 执行阻塞队列
     */
   /* //创建阻塞队列，拿来装订单
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
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
*/



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

        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本， 将消息传入 stream队列

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,//判断购买资格
                Collections.emptyList(),
                voucherId.toString(),//发送消息到stream消息队列
                userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        //2.1判断结果不为0
        if (r != 0){
            //2.2判断结果为0
            return Result.fail(r == 1 ?"库存不足":"不能重复下单");
        }


        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(orderId);
        //7.2填入用户id
        voucherOrder.setUserId(userId);
        //7.3填入秒杀券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        //orderTasks.add(voucherOrder);
        //因为上面lua脚本已经把东西丢到stream消息队列了，所以不用这个了

        //获取代理对象（开启事务，作用是，保证扣减库存和创建订单同时成功或失败，就是保障createVoucherOrder方法里内容一定执行成功）
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //
        //4.返回订单id
        return  Result.ok(orderId);
       /* //1.执行lua脚本,将消息传入   阻塞队列
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
        return  Result.ok(orderId);*/
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
