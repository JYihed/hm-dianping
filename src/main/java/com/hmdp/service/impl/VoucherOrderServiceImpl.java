package com.hmdp.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
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
import com.rabbitmq.client.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chenjy
 * @since 2024-4-12
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
    private RabbitTemplate rabbitTemplate = SpringUtil.getBean(RabbitTemplate.class);
    //3.获取代理对象
    private IVoucherOrderService proxy;


    //脚本静态代码块
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    @Component
    public class VoucherOrderHandler {
        @Autowired
        private IVoucherOrderService proxy;
        @Autowired
        private RedissonClient redissonClient;

        @Async
        @RabbitListener(queues = "voucher.order")
        public void handleVoucherOrder(VoucherOrder voucherOrder) {
            try {
                System.err.println("接收到消息：" + voucherOrder.getUserId());
                // 处理消息
                // 处理订单逻辑
                if (voucherOrder!=null){
                    Long userId = voucherOrder.getUserId();
                    //创建锁对象
                    RLock lock = redissonClient.getLock("lock:order:" + userId);
                    boolean islock = lock.tryLock();
                    if(!islock){
                        log.error("不允许重复下单");
                        return ;
                    }
                    try {
                        //7.返回订单id
                        System.out.println("jjking");
                        proxy.createVoucherOrder(voucherOrder);
                    } finally {
                        //释放锁
                        lock.unlock();
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                // 可以根据需要处理异常情况
            }
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断脚本结果是否为0
        int r = result.intValue();
        if(r != 0) {
            //2.1.不为0，没有购买资格
            return Result.fail(r==1?"库存不足！":"不可以重复下单哦");
        }
        //2.2.为0，有购买资格，保存下单信息到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4.用户id
        voucherOrder.setUserId(userId);
        //2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6.创建阻塞队列
        //orderTasks.add(voucherOrder);
        rabbitTemplate.convertAndSend("voucher.order",voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);

    }

    @Transactional
    public  void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId = voucherOrder.getUserId();
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
            log.error("重复下单");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update(); //乐观锁
        if(!success){
            log.error("库存不足");
        }
        save(voucherOrder);

    }
}

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        //3.判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//        //4.判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足！！");
//        }
//        Long useId = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + useId);
//        RLock lock = redissonClient.getLock("lock:order:" + useId);
//        boolean islock = lock.tryLock();
//        if(!islock){
//            return Result.fail("请勿重复下单！");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //7.返回订单id
//            return proxy.createVoucherOrder(voucherId,useId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }


//    @PostConstruct
//    private void init(){
//        System.out.println("线程已经创建");
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

//阻塞队列
//private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//已被淘汰
//    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
//        Long userId = voucherOrder.getUserId();
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + useId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean islock = lock.tryLock();
//        if(!islock){
//            log.error("不允许重复下单");
//            return ;
//        }
//        try {
//            //7.返回订单id
//            System.out.println("jjking");
//            proxy.createVoucherOrder(voucherOrder);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

// 用rabbitMQ来替代阻塞队列
//线程池
//private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//线程任务
//    public class VoucherOrderHandler implements Runnable {
//        private static final String QUEUE_NAME = "voucher.order";
//        private static final String HOST = "118.31.37.29";
//        private static final int PORT = 5672;
//        private static final String USERNAME = "root";
//        private static final String PASSWORD = "root";
//        @Override
//        public void run() {
//            try {
//                ConnectionFactory factory = new ConnectionFactory();
//                factory.setHost(HOST);
//                factory.setPort(PORT);
//                factory.setUsername(USERNAME);
//                factory.setPassword(PASSWORD);
//                Connection connection = factory.newConnection();
//                Channel channel = connection.createChannel();
//                System.err.println("大外部");
//                // 创建消费者
//                Consumer consumer = new DefaultConsumer(channel) {
//                    @Override
//                    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
//                        String message = new String(body);
//                        System.err.println("接收到消息：" + message);
//                        // 处理消息
//                        VoucherOrder voucherOrder = JSONObject.parseObject(message, VoucherOrder.class);
//                        handlerVoucherOrder(voucherOrder);
//                        // 手动确认消息已被消费
//                        channel.basicAck(envelope.getDeliveryTag(), false);
//                    }
//                };
//                // 注册消费者
//                channel.basicConsume(QUEUE_NAME, false, consumer);
//                // 保持线程运行，持续监听消息
//                while (true) {
//                    Thread.sleep(1000);
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//            }
//        }
