package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    public void testRedisIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for (int i = 0; i < 100; i++) {
                System.out.println("id:" + redisIdWorker.nextId("order"));
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            new Thread(runnable).start();
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }
}
