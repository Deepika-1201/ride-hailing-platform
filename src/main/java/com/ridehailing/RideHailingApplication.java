package com.ridehailing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RideHailingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideHailingApplication.class, args);
    }
}
