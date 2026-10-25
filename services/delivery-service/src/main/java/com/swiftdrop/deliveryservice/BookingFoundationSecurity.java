package com.swiftdrop.deliveryservice;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@Configuration(proxyBeanMethods=false)
public class BookingFoundationSecurity {
 @Bean
 @Order(2)
 SecurityFilterChain foundationSecurity(HttpSecurity http) throws Exception {
  return http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
   .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
   .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll()).build();
 }
}
