package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * 惠享生活 - 本地生活优惠与智能服务平台 启动类
 */
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class HuiXiangApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuiXiangApplication.class, args);
    }

}
