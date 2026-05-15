package com.zxl.agi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

/**
 * @Author: zhuxl
 * @Date: 2026/5/15 18:40
 * @Description: com.zxl.agi
 * @version: 1.0
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
public class AgiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgiAgentApplication.class, args);
    }
}
