package com.myapp.infrastructure.vector.milvus;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "milvus")
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusConfig {
    @Bean
    JsonHttpClient milvusHttpClient(MilvusProperties properties, ObjectMapper objectMapper) {
        Map<String, String> headers = properties.getToken().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getToken());
        return new JsonHttpClient(properties.getBaseUrl(), headers, objectMapper);
    }

    @Bean
    MilvusVectorStore milvusVectorStore(JsonHttpClient client, MilvusProperties properties) {
        return new MilvusVectorStore(client, properties);
    }

    @Bean
    MilvusIndexManager milvusIndexManager(JsonHttpClient client, MilvusProperties properties) {
        return new MilvusIndexManager(client, properties);
    }
}
