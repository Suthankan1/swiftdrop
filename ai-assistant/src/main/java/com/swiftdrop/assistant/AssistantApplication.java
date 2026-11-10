package com.swiftdrop.assistant;

import com.swiftdrop.security.jwt.SwiftDropSecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** SwiftDrop delivery assistant: Gemini function calling over the existing read APIs. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(SwiftDropSecurityConfiguration.class)
public class AssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
