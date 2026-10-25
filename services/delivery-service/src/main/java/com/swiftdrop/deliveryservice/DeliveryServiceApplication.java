package com.swiftdrop.deliveryservice;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
@SpringBootApplication
public class DeliveryServiceApplication {
 public static void main(String[] args) { SpringApplication.run(DeliveryServiceApplication.class, args); }
 @Bean Clock clock() { return Clock.systemUTC(); }
}
