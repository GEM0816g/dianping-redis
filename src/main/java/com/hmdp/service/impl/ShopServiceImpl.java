package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 梁同学
 * @since 2023-2-2
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //利用缓存空值解决缓存穿透
        //    Shop shop = queryWithPassYhrough(id);

        //利用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        //利用逻辑过期解决缓存击穿
      //  queryWithLogicalExpire(id);
        if (shop==null){
            return Result.fail("店铺信息不存在");
        }else{
            return Result.ok(shop);
        }

    }

    public Shop queryWithPassYhrough(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);

            return shop;
        }
        //只有缓存判断命中的是否是空值  "" 的值即为缓存的空值
        if (shopJson !=null){
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop=getById(id);
        //5.不存在，返回错误
        if (shop==null){
            //解决缓存穿透问题，如果有恶意访问不存在的数据
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return  null;
        }

        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //7.返回
        return shop;
    }
    public Shop queryWithMutex(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);

            return shop;
        }
        //只有缓存判断命中的是否是空值  "" 的值即为缓存的空值
        if (shopJson !=null){
            return null;
        }

        //4.实现缓存重构
        //4.1获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop = null;
        try {
        boolean trylock = trylock(lockKey);
        //4.2判断是否获取成功
        if (!trylock){
            //4.3失败，则休眠并重试

                Thread.sleep(50);

             return queryWithMutex(id);
        }

        //4.4成功，根据id查询数据库，并构建缓存
        shop=getById(id);
        //5.不存在，返回错误
        if (shop==null){
            //解决缓存穿透问题，如果有恶意访问不存在的数据
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return  null;
        }

        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
           // e.printStackTrace();
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }



        //8.返回
        return shop;
    }
    //设置互斥锁
    public boolean trylock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    //释放锁
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }



    public Shop queryWithLogicalExpire(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
        }

        //5.2已过期，需要缓存重建

        //6.实现缓存重构
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean islock = trylock(lockKey);
        //6.2 判断是否获取锁成功
        if (islock){
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4不成功，返回旧数据
        return shop;
    }
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateAndRedis(Shop shop) {
        if (shop.getId()==null){
            return Result.fail("id不能为空");
        }
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return null;
    }
}
