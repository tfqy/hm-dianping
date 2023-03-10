package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 计算分页起始位置
        int start = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        // 计算分页结束位置
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 查询redis中的店铺信息，按照距离排序
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                RedisConstants.SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= start) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        list.stream().skip(start).forEach(geoResult -> {
            String shopId = geoResult.getContent().getName();
            Distance distance = geoResult.getDistance();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, distance);
        });
        //5. 根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            //设置shop的举例属性，从distanceMap中根据shopId查询
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6. 返回
        return Result.ok(shops);
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
