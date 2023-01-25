package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 2023/1/25 14:05
 *
 * @author tfqy
 */

@Component
public class RedisIdWorker {

    //开始时间截 (2023-01-01)
    private final long BEGIN_TIMESTAMP = 1674626934L;

    // 机器id所占的位数
    private final int WORKER_ID_BITS = 32;

    private final StringRedisTemplate redisTemplate;

    public RedisIdWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
//        自增长
        long count = redisTemplate.opsForValue().increment(RedisConstants.ICR_ID_KEY + keyPrefix + ":" + date);
        return timestamp << WORKER_ID_BITS | count;
    }
}
