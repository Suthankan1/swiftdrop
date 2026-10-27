package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring configuration providing standard JDK HttpClient and default WebhookHttpClient bean.
 */
@Configuration
public class WebhookDispatchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(HttpClient.class)
    public HttpClient webhookJdkHttpClient(WebhookDispatchProperties properties) {
        log.info("Configuring Webhook JDK HttpClient [connectTimeout={}ms, followRedirects=NEVER]",
                properties.getConnectTimeoutMs());
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(WebhookHttpClient.class)
    public WebhookHttpClient defaultWebhookHttpClient(
            HttpClient webhookJdkHttpClient,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties properties) {
        return new DefaultWebhookHttpClient(webhookJdkHttpClient, destinationValidator, properties);
    }

    @Bean
    @ConditionalOnMissingBean(java.time.Clock.class)
    public java.time.Clock webhookClock() {
        return java.time.Clock.systemUTC();
    }
}
