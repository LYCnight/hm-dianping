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
import org.springframework.aop.framework.AopContext;
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
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            // 尚未开始
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            // 尚未开始
            return Result.fail("秒杀已经结束");
        }
        // 4. 判断库存是否充足
        if(voucher.getStock() < 1){
            // 库存不足
            return Result.fail("库存不足");
        }
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // 拿到代理对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            // 用代理对象调用 createVoucher 方法，才能让事务 @Transactional 生效
            return proxy.createVoucher(voucherId);
        }
    }

    @Transactional  // 事务
    public Result createVoucher(Long voucherId){
        // 5. 一人一单
        Long userId = UserHolder.getUser().getId();
        // 所以每个线程执行该方法都会新生成一个User对象，当toString的时候又新new一个String对象
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断该订单是否已经存在
        if(count > 0){
            // 用户已经购买过一单了
            return Result.fail("用户已经购买过一次了");
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")    // SET stock = stock - 1
                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())    // where id = ? and stock = ?
                .gt("stock", 0)     // where id = ? and stock > 0
                .update();
        if(!success){
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7. 创建库存
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 用户id
//        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 8. 返回订单id
        return Result.ok(orderId);
    }
}
