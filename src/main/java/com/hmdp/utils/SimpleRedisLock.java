package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 2023/1/27 14:13
 *
 * @author tfqy
 */

public class SimpleRedisLock implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private final String name;
    private final StringRedisTemplate redisTemplate;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁的线程标识
//        String lockThreadId = redisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否是同一个线程
//        if (threadId.equals(lockThreadId)) {
//            //删除锁
//            redisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

}
