package xsl.sai.framework.config;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 客户端配置
 * &#064;DATE: 2026/5/13 14:14
 * &#064;AUTHOR: XSL
 *
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Redis地址
     */
    @Value("${spring.data.redis.host}")
    public String host;

    /**
     * Redis端口
     */
    @Value("${spring.data.redis.port:6379}")
    public int port;

    /**
     * Redis数据库
     */
    @Value("${spring.data.redis.database:0}")
    public int dataBase;

    /**
     * Redis密码
     */
    @Value("${spring.data.redis.password:}")
    public String password;

    /**
     * 是否在连不上 Redis 时阻断启动（个人自用：本机未装 Redis 时设为 false，不阻断）
     */
    @Value("${spring.data.redis.fail-fast:false}")
    public boolean failFast;

    @Bean
    public RedissonClient redissonClient() {
        try {
            int cpuThread = Runtime.getRuntime().availableProcessors();
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + host + ":" + port)
                    .setDatabase(dataBase)  //指定库
                    .setConnectionMinimumIdleSize(cpuThread / 2) // 最小空闲连接数
                    .setConnectionPoolSize(cpuThread)         // 连接池大小
                    .setConnectTimeout(3000)           // 连接超时时间
                    .setTimeout(3000)                 // 命令执行超时时间
                    .setIdleConnectionTimeout(10000);  // 空闲连接超时时间;
            config.setCodec(new JsonJacksonCodec());    //配置序列化
            if (StrUtil.isNotEmpty(password)) {
                config.setPassword(password);
            }
            RedissonClient client = Redisson.create(config);
            log.info("Redis 客户端初始化成功：redis://{}:{}", host, port);
            return client;
        } catch (Exception e) {
            if (failFast) {
                throw e;
            }
            log.warn("Redis 连接失败，已跳过初始化（应用继续启动）：{}", e.getMessage());
            return null;
        }
    }

    @Bean
    public RedissonReactiveClient reactiveClient(RedissonClient redissonClient) {
        if (redissonClient == null) {
            return null;
        }
        return redissonClient.reactive();
    }


}
