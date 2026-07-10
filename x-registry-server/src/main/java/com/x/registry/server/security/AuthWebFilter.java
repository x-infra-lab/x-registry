package com.x.registry.server.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring WebFlux filter that validates Bearer tokens on HTTP requests.
 * Skips actuator endpoints to allow health checks and metrics scraping.
 */
public class AuthWebFilter implements WebFilter {

    private final TokenStore tokenStore;

    public AuthWebFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = extractBearerToken(authHeader);

        AuthToken authToken = tokenStore.validate(token);
        if (authToken == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        exchange.getAttributes().put("authToken", authToken);
        return chain.filter(exchange);
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }
}
