package xsl.sai.framework.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.framework.holder.SpringHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * redisson会自动选择序列化反序列化方式
 * &#064;DATE:  2024/6/2
 * &#064;AUTHOR:  XSL
 *
 */
@Slf4j
@Component
public class RedisClient {

    private static RedissonReactiveClient redissonReactiveClient;

    /**
     * 同步客户端。分布式锁只能用同步 API（原因见 {@link #syncLock(String)}），
     * 响应式客户端拿不到同步锁对象。
     */
    private static RedissonClient redissonClient;

    @PostConstruct
    public void init() {
        try {
            RedisClient.redissonReactiveClient = SpringHolder.getBean(RedissonReactiveClient.class);
        } catch (Exception e) {
            // Redis 不可用（如 fail-fast=false 时未注册 bean）时，不阻断应用启动，仅告警
            log.warn("RedissonReactiveClient 未初始化（Redis 可能不可用），Redis 相关缓存功能将不可用：{}", e.getMessage());
            RedisClient.redissonReactiveClient = null;
        }
        try {
            RedisClient.redissonClient = SpringHolder.getBean(RedissonClient.class);
        } catch (Exception e) {
            log.warn("RedissonClient 未初始化（Redis 可能不可用），分布式锁功能将不可用：{}", e.getMessage());
            RedisClient.redissonClient = null;
        }
    }

    /**
     * 统一获取 RedissonReactiveClient，Redis 不可用（未初始化）时抛出明确异常，避免后续 NPE。
     */
    private static RedissonReactiveClient client() {
        if (redissonReactiveClient == null) {
            throw new IllegalStateException("Redis 客户端未初始化，Redis 不可用");
        }
        return redissonReactiveClient;
    }

    /**
     * 读取缓存（对象类型）
     *
     * @param key 缓存key
     * @param <T> 返回值类型
     * @return Mono包装的缓存值
     */
    public static <T> Mono<T> getObject(String key) {
        RBucketReactive<T> bucket = client().getBucket(key);
        return bucket.get();
    }

    /**
     * 以string的方式读取缓存
     *
     * @param key 缓存key
     * @return Mono包装的字符串值
     */
    public Mono<String> getStr(String key) {
        RBucketReactive<String> bucket = client().getBucket(key, StringCodec.INSTANCE);
        return bucket.get();
    }

    /**
     * 设置缓存对象
     *
     * @param key   缓存key
     * @param value 缓存值
     * @param <T>   值类型
     * @return Mono<Void> 表示操作完成
     */
    public <T> Mono<Void> setObject(String key, T value) {
        return client().getBucket(key).set(value).then();
    }

    /**
     * 设置缓存对象（带过期时间）
     *
     * @param key      缓存key
     * @param value    缓存值
     * @param duration 缓存时间
     * @param <T>      值类型
     * @return Mono<Void> 表示操作完成
     */
    public <T> Mono<Void> setObject(String key, T value, Duration duration) {
        return client().getBucket(key).set(value, duration).then();
    }

    /**
     * 设置缓存字符串（带过期时间）
     *
     * @param key      缓存key
     * @param value    缓存值
     * @param duration 缓存时间
     * @return Mono<Void> 表示操作完成
     */
    public Mono<Void> setStr(String key, String value, Duration duration) {
        return client().getBucket(key, StringCodec.INSTANCE).set(value, duration).then();
    }

    /**
     * 移除缓存
     *
     * @param key 缓存key
     * @return Mono<Boolean> 是否删除成功
     */
    public Mono<Boolean> remove(String key) {
        return client().getBucket(key).delete();
    }

    /**
     * 判断缓存是否存在
     *
     * @param key 缓存key
     * @return Mono<Boolean> 是否存在
     */
    public Mono<Boolean> exists(String key) {
        return client().getBucket(key).isExists();
    }

    /**
     * 设置或刷新缓存时间
     *
     * @param key      缓存key
     * @param duration 过期时间
     * @return Mono<Boolean> 是否设置成功
     */
    public Mono<Boolean> expire(String key, Duration duration) {
        return client().getBucket(key).expire(duration);
    }

    /**
     * 获取剩余时间（毫秒）
     *
     * @param key 缓存key
     * @return Mono<Long> 剩余时间（毫秒），-1表示永久，-2表示不存在
     */
    public Mono<Long> getTTL(String key) {
        return client().getBucket(key).remainTimeToLive();
    }

    /**
     * 获取RList对象（响应式）
     *
     * @param key 缓存key
     * @param <T> 元素类型
     * @return RListReactive响应式列表
     */
    public <T> RListReactive<T> getList(String key) {
        return client().getList(key);
    }

    /**
     * 获取RMapCache对象（响应式）
     *
     * @param key 缓存key
     * @param <K> key类型
     * @param <V> value类型
     * @return RMapCacheReactive响应式Map
     */
    public <K, V> RMapCacheReactive<K, V> getMap(String key) {
        return client().getMapCache(key);
    }

    /**
     * 获取RSet对象（响应式）
     *
     * @param key 缓存key
     * @param <T> 元素类型
     * @return RSetReactive响应式Set
     */
    public <T> RSetReactive<T> getSet(String key) {
        return client().getSet(key);
    }

    /**
     * 获取RScoredSortedSet对象（响应式）
     *
     * @param key 缓存key
     * @param <T> 元素类型
     * @return RScoredSortedSetReactive响应式有序集合
     */
    public <T> RScoredSortedSetReactive<T> getScoredSortedSet(String key) {
        return client().getScoredSortedSet(key);
    }

    /**
     * 获取响应式锁
     *
     * <p><b>注意：不要用它做分布式互斥</b>。Redisson 的 {@code RLockReactive} 只是同步
     * {@code RedissonLock} 的动态代理（见 {@code RedissonReactive#getLock} →
     * {@code ReactiveProxyBuilder.create}），锁归属「加锁那一刻的线程」；Reactor 中加锁与解锁
     * 通常落在不同线程（R2DBC / 模型回调各在自己的线程池），{@code unlock()} 会抛
     * {@code IllegalMonitorStateException}，锁只能等看门狗超时后自然释放。
     * 需要分布式锁请用 {@link #syncLock(String)}。
     */
    public static RLockReactive lock(String key) {
        return client().getLock(key);
    }

    /**
     * 获取<b>同步</b>分布式锁（可重入，由 Redisson 看门狗自动续期）；
     * Redis 不可用时返回 {@code null}，由调用方自行降级（无锁执行）。
     *
     * <p><b>为什么必须是同步锁</b>：见 {@link #lock(String)} 的说明——响应式锁绑定调用线程，
     * 在 Reactor 里加锁/解锁往往不是同一个线程。用同步锁时，务必保证
     * 「加锁 → 业务 → 解锁」在<b>同一个线程</b>内完成，典型写法：
     * <pre>{@code
     * Mono.fromCallable(() -> {
     *     RLock lock = RedisClient.syncLock("sai:lock:xxx");
     *     if (lock == null) { return doWork().block(); }        // Redis 不可用：降级
     *     if (!lock.tryLock()) { return 0L; }                   // 没抢到：直接跳过
     *     try { return doWork().block(); }                      // 同一线程内阻塞等待业务完成
     *     finally { if (lock.isHeldByCurrentThread()) { lock.unlock(); } }
     * }).subscribeOn(Schedulers.boundedElastic());              // 阻塞任务放到弹性线程池
     * }</pre>
     *
     * @param key 锁的 Redis key
     * @return 同步锁对象；Redis 不可用时为 {@code null}
     */
    public static RLock syncLock(String key) {
        return redissonClient == null ? null : redissonClient.getLock(key);
    }

    /**
     * 原子自增（返回自增前的值）
     *
     * @param key 缓存key
     * @return Mono<Long> 自增前的值
     */
    public Mono<Long> getAndIncrement(String key) {
        return client().getAtomicLong(key)
                .getAndIncrement();
    }

    /**
     * 原子自增（返回自增后的值）
     *
     * @param key 缓存key
     * @return Mono<Long> 自增后的值
     */
    public Mono<Long> incrementAndGet(String key) {
        return client().getAtomicLong(key)
                .incrementAndGet();
    }

    /**
     * 原子自减（返回自减后的值）
     *
     * @param key 缓存key
     * @return Mono<Long> 自减后的值
     */
    public Mono<Long> decrementAndGet(String key) {
        return client().getAtomicLong(key)
                .decrementAndGet();
    }

    /**
     * 获取当前值并自减1
     *
     * @param key 缓存key
     * @return Mono<Long> 自减前的值
     */
    public Mono<Long> getAndDecrement(String key) {
        return client().getAtomicLong(key)
                .getAndDecrement();
    }

    /**
     * 增加指定数量（可以是负数）
     *
     * @param key   缓存key
     * @param delta 增加的数量（负数表示减少）
     * @return Mono<Long> 增加后的值
     */
    public Mono<Long> addAndGet(String key, long delta) {
        return client().getAtomicLong(key)
                .addAndGet(delta);
    }

    /**
     * 安全的减1操作（确保结果不为负数）
     * 注意：由于CAS操作是阻塞的，需要使用boundedElastic
     *
     * @param key 缓存key
     * @return Mono<Long> 减1后的值，如果减1后会小于0则返回当前值不做减法
     */
    public Mono<Long> decrementIfGreaterThanZero(String key) {
        if (redissonReactiveClient == null) {
            return Mono.error(new IllegalStateException("Redis 客户端未初始化，Redis 不可用"));
        }
        return Mono.fromCallable(() -> {
            RAtomicLong counter = SpringHolder.getBean(RedissonClient.class).getAtomicLong(key);
            while (true) {
                long current = counter.get();
                if (current <= 0) {
                    return current;
                }
                if (counter.compareAndSet(current, current - 1)) {
                    return current - 1;
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取原子长整型对象（响应式）
     *
     * @param key 缓存key
     * @return RAtomicLongReactive响应式原子长整型
     */
    public RAtomicLongReactive getAtomicLong(String key) {
        return client().getAtomicLong(key);
    }

    /**
     * 批量获取缓存值
     *
     * @param keys 多个key
     * @return Flux包含所有存在的值
     */
    public <T> Flux<T> multiGet(List<String> keys) {
        return Flux.fromIterable(keys)
                .flatMap(key -> client().<T>getBucket(key).get())
                .filter(Objects::nonNull);
    }

    /**
     * 批量设置缓存（统一过期时间）
     *
     * @param keyValues key-value对
     * @param duration  过期时间
     * @return Mono<Void>
     */
    public Mono<Void> multiSet(Map<String, Object> keyValues, Duration duration) {
        return Flux.fromIterable(keyValues.entrySet())
                .flatMap(entry -> setObject(entry.getKey(), entry.getValue(), duration))
                .then();
    }


}
