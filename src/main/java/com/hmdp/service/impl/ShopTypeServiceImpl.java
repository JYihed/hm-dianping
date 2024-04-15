package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        //1.查询redis中是否有信息
        List<String> shopJSONlist= stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE,0,-1);
        if(!CollectionUtils.isEmpty(shopJSONlist)){
            //2.有，直接返回
            log.debug("缓存！！！");
            List<ShopType> shopTypeList = shopJSONlist.stream()
                    .map(s -> JSONUtil.toBean(s,ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        //3.无，查询数据库中是否存在
        List<ShopType> shopTypeList = lambdaQuery().orderByAsc(ShopType::getSort).list();
        if(CollectionUtils.isEmpty(shopTypeList)){
            //3.1.无，返回错误
            return Result.fail("无商铺分类信息");
        }
        //3.2.有，存入redis
        log.debug("数据库！！！");
        List<String> shopCache = shopTypeList.stream()
                .sorted(Comparator.comparing(ShopType::getSort))
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE,shopCache);
        stringRedisTemplate.expire(CACHE_SHOP_TYPE,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //4.返回
        return Result.ok(shopTypeList);

    }
}
