package com.x.registry.server.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;

@Configuration
public class WebResourceConfig {

    @Bean
    public RouterFunction<ServerResponse> consoleRedirect() {
        return RouterFunctions.route()
                .GET("/console", req -> ServerResponse.permanentRedirect(URI.create("/console/index.html")).build())
                .build();
    }
}
