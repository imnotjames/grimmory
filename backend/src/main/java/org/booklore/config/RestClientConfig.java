package org.booklore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {
    private ClientHttpRequestFactory getRequestFactory(HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    @Bean
    public RestClient restClient(HttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(getRequestFactory(httpClient))
                .build();
    }

    @Bean
    @Primary
    public RestTemplate restTemplate(HttpClient httpClient) {
        return new RestTemplate(getRequestFactory(httpClient));
    }

    @Bean
    public RestTemplate oidcRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
