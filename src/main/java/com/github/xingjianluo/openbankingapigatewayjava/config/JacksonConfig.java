package com.github.xingjianluo.openbankingapigatewayjava.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 序列化约定：时间统一 ISO-8601 字符串，不输出时间戳数组。
 * 未知请求字段的容忍读取在 {@code PaymentBodyParser} 中显式配置。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer isoTimeCustomizer() {
        return builder -> builder
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
