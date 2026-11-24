package com.kiran.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PiiVaultApplication {
    public static void main(String[] args) {
        SpringApplication.run(PiiVaultApplication.class, args);
    }
}
