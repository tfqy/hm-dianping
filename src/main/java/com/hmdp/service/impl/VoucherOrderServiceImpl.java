package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    private final RedisIdWorker redisIdWorker;

    public VoucherOrderServiceImpl(SeckillVoucherServiceImpl seckillVoucherService, RedisIdWorker redisIdWorker) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisIdWorker = redisIdWorker;
    }

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀已经结束
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        //5，扣减库存
        boolean success = seckillVoucherService.update()
//                .set(true, "stock", voucher.getStock() - 1)   //如果使用这个方法，会出现并发问题，因为这个方法是先查询再更新，会导致更新的时候库存已经被别人更新了
                .setSql("stock = stock -1") //这个方法是直接执行sql语句，不会出现并发问题，但是这个方法不会判断库存是否为0，所以需要在下面判断库存是否为0
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);

    }
}
