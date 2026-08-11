package com.mrrorigin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MrrOriginApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrrOriginApplication.class, args);
    }
}
