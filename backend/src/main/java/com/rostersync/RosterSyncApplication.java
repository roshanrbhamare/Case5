package com.rostersync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RosterSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(RosterSyncApplication.class, args);
    }
}
