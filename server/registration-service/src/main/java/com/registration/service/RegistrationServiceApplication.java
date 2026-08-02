package com.registration.service;

import com.registration.service.config.RegistrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistrationServiceApplication.class, args);
    }
}
