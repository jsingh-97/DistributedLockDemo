package io.github.jaswinder.distributedlockdemo.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Redisson configuration.
 *
 * The redisson-spring-boot-starter would auto-configure a RedissonClient for us
 * from the spring.data.redis.* properties, but we define it here explicitly so
 * the wiring is visible and easy to customise (connection pools, codecs, cluster
 * settings, sentinel, etc.)
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(String.format("redis://%s:%d", redisHost, redisPort))
                .setConnectionMinimumIdleSize(5)
                .setConnectionPoolSize(20)
                .setConnectTimeout(3_000)
                .setTimeout(3_000);
        return Redisson.create(config);
    }
}

