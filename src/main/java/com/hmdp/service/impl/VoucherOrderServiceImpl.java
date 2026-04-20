package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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

        //5.充足，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).update();
        //6.若库存扣减失败，返回异常结果
        if (!success){
            return Result.fail("库存不足");
        }
        //7.库存扣减成功，创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1填入订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2填入用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //7.3填入秒杀券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //8.返回订单
        return Result.ok(orderId);
    }
}
