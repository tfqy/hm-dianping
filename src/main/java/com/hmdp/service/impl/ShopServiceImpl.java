package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate redisTemplate;
    private final CacheClient cacheClient;

    @Autowired
    public ShopServiceImpl(StringRedisTemplate redisTemplate, CacheClient cacheClient) {
        this.redisTemplate = redisTemplate;
        this.cacheClient = cacheClient;
    }

    @Override
    public Result queryById(Long id) {
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                        this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithPassThrough(Long id) {
        // 从redis中获取店铺信息
        String shopInfo = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 判断redis中是否有店铺信息
        if (StrUtil.isNotBlank(shopInfo)) {
            return JSONUtil.toBean(shopInfo, Shop.class);
        }
        if (shopInfo != null) {
            return null;
        }
        // 从数据库中查询店铺信息
        Shop shop = this.getById(id);
        // 判断店铺是否存在
        if (shop == null) {
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                    "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 将店铺信息存入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        // 从redis中获取店铺信息
        String shopInfo = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 判断redis中是否有店铺信息
        if (StrUtil.isNotBlank(shopInfo)) {
            return JSONUtil.toBean(shopInfo, Shop.class);
        }
        if (shopInfo != null) {
            return null;
        }
        // 尝试获取redis锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            if (!isLock) {
                // 获取锁失败，等待一段时间后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 从数据库中查询店铺信息
            shop = this.getById(id);
            //模拟数据库查询耗时
            Thread.sleep(200);
            // 判断店铺是否存在
            if (shop == null) {
                redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                        "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 将店铺信息存入redis
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                    JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放redis锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    public Shop queryWithLogicalExpire(Long id) {
        // 从redis中获取店铺信息
        String shopInfo = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 判断redis中是否有店铺信息
        if (StrUtil.isBlank(shopInfo)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopInfo, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断店铺信息是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 已过期，需要缓存重建
        // 尝试获取redis锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        // 判断是否获取锁成功
        if (isLock) {
            // 成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放redis锁
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新店铺信息
        updateById(shop);
        // 删除redis中的店铺信息
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    private boolean tryLock(String key) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS));
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Thread.sleep(200);
        Shop shop = this.getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
