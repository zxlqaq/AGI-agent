package com.zxl.agi.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {
    private final AppConfig appConfig;

    @Bean
    public ElasticsearchClient elasticsearchClient() {

        URI uri = URI.create(appConfig.getElasticsearch().getAddresses());

        RestClient restClient = RestClient.builder(
                new HttpHost(
                        uri.getHost(),
                        uri.getPort(),
                        uri.getScheme()
                )
        ).build();

        ElasticsearchTransport transport =
                new RestClientTransport(
                        restClient,
                        new JacksonJsonpMapper()
                );

        return new ElasticsearchClient(transport);
    }
}
