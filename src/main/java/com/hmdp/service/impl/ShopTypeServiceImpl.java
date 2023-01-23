package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate redisTemplate;

    @Autowired
    public ShopTypeServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Result queryList() {
        // 从redis中获取店铺类型信息
        List<String> shopTypesJson = redisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        // 判断redis中是否有店铺类型信息
        //如果有数据，转为List<ShopType>返回
        if (shopTypesJson != null && shopTypesJson.size() > 0) {
            List<ShopType> shopTypes = new ArrayList<>();
            for (String shopTypeJson : shopTypesJson) {
                shopTypes.add(JSONUtil.toBean(shopTypeJson, ShopType.class));
            }
            return Result.ok(shopTypes);
        }
        // 从数据库中查询店铺类型信息
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes != null && shopTypes.size() > 0) {
            // 将店铺类型信息存入redis
            for (ShopType shopType : shopTypes) {
                redisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType));
            }
            return Result.ok(shopTypes);
        }
        return Result.fail("店铺类型不存在");
    }
}
