package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    // redis 集群-节点1
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://8.152.211.25:6379")
                .setPassword("linux369");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }

    // redis 集群-节点2
    @Bean
    public RedissonClient redissonClient2(){
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://8.152.211.25:6380")
                .setPassword("linux369");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }

    // redis 集群-节点3
    @Bean
    public RedissonClient redissonClient3(){
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://8.152.211.25:6381")
                .setPassword("linux369");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }
}
