package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    public void testIdworker(){

        CountDownLatch latch=new CountDownLatch(300);
        Runnable task=()->{
          for (int i =0;i<100;i++){
              long order = redisIdWorker.nextId("order");
              System.out.println("id="+order);

          }
          latch.countDown();
        };
        long begin=System.currentTimeMillis();
        for (int i =0;i<300;i++){
            es.submit(task);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        long end= System.currentTimeMillis();
        System.out.println("time"+(end-begin));
    }
}
