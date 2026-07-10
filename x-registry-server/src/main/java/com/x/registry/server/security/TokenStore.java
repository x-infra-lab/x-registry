package com.x.registry.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private final Map<String, AuthToken> tokens = new ConcurrentHashMap<>();

    public void addToken(AuthToken token) {
        tokens.put(token.getSecret(), token);
        log.info("Registered auth token: {}", token.getId());
    }

    public AuthToken validate(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return tokens.get(secret);
    }

    public int size() {
        return tokens.size();
    }
}
