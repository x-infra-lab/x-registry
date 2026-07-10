package com.x.registry.server.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.x.registry.server")
public class XRegistryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(XRegistryServerApplication.class, args);
    }
}
