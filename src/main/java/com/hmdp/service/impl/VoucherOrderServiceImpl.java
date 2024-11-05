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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // lua
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 代理对象
    private IVoucherOrderService proxy;

    // 秒杀-主函数
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");   // 生成下单id
        // 1. 执行 lua 脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        // 2. 判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3. 获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        // 4. 返回订单id
        return Result.ok(orderId);
    }

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 提交线程
    @PostConstruct // 作用：当前类初始化完后马上就执行被注解的方法 (这是一个 Spring注解)
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 异步下单线程类
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";

        @Override
        public void run() {
            while(true){
                try {
                    // 1. 获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1 count 1 BLOCK 2000 STREAMS stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())

                    );
                    // 2. 判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 4. 如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK确认
                    // XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 未确认的消息会被放入 pending-list 中
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            while(true){
                try {
                    // 1. 获取pedning-list中的订单信息
                    // XREADGROUP GROUP g1 c1 count 1 STREAMS stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1 如果获取失败，说明 pending-list 中没有异常消息，则结束循环
                        break;
                    }

                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 4. 如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK确认
                    // XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理 pending-list 订单异常", e);
                    // 处理 pending-list 时又发生异常，继续重试
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    // 下单函数 - 应用层（锁）
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 尝试获取锁
        boolean isLock = lock.tryLock();
        // 4. 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或者重试
            log.error("不允许重复下单");
            return;
        }
        // 5. 获取锁成功，走购买的逻辑
        try {
            // 用代理对象调用 createVoucher 方法，才能让事务 @Transactional 生效
            proxy.createVoucher(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 下单函数 - 数据层（操作数据库）
    @Transactional  // 事务
    public void createVoucher(VoucherOrder voucherOrder){
        // 5. 一人一单
        Long userId = voucherOrder.getUserId();
        // 所以每个线程执行该方法都会新生成一个User对象，当toString的时候又新new一个String对象
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        // 5.2 判断该订单是否已经存在
        if(count > 0){
            // 用户已经购买过一单了
            log.error("用户已经购买过一次！不允许同一个用户重复购买同一个优惠券");
            return;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")    // SET stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())
//                .eq("stock", voucher.getStock())    // where id = ? and stock = ?
                .gt("stock", 0)     // where id = ? and stock > 0
                .update();
        if(!success){
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 7. 创建订单
        save(voucherOrder);
    }
}
