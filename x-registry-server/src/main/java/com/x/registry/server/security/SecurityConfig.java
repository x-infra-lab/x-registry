package com.x.registry.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(name = "x-registry.auth.enabled", havingValue = "true")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "x-registry.auth")
    public AuthProperties authProperties() {
        return new AuthProperties();
    }

    @Bean
    public TokenStore tokenStore(AuthProperties props) {
        TokenStore store = new TokenStore();
        String encryptionKey = props.getEncryptionKey();
        if (props.getTokens() != null) {
            for (AuthProperties.TokenDef def : props.getTokens()) {
                String secret = def.getSecret();
                if (encryptionKey != null && !encryptionKey.isEmpty()
                        && TokenEncryptor.isEncrypted(secret)) {
                    secret = TokenEncryptor.decrypt(secret, encryptionKey);
                }
                AuthToken token = new AuthToken(
                        def.getId(),
                        secret,
                        Set.copyOf(def.getNamespaces()),
                        def.getPermissions().stream()
                                .map(Permission::valueOf)
                                .collect(Collectors.toSet())
                );
                store.addToken(token);
            }
        }
        log.info("Security enabled: {} tokens loaded, encryption={}",
                store.size(), encryptionKey != null && !encryptionKey.isEmpty());
        return store;
    }

    @Bean
    public AuthInterceptor authInterceptor(TokenStore tokenStore) {
        return new AuthInterceptor(tokenStore);
    }

    @Bean
    public AuthWebFilter authWebFilter(TokenStore tokenStore) {
        return new AuthWebFilter(tokenStore);
    }

    public static class AuthProperties {
        private boolean enabled;
        private String encryptionKey;
        private List<TokenDef> tokens = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEncryptionKey() {
            return encryptionKey;
        }

        public void setEncryptionKey(String encryptionKey) {
            this.encryptionKey = encryptionKey;
        }

        public List<TokenDef> getTokens() {
            return tokens;
        }

        public void setTokens(List<TokenDef> tokens) {
            this.tokens = tokens;
        }

        public static class TokenDef {
            private String id;
            private String secret;
            private List<String> namespaces = new ArrayList<>();
            private List<String> permissions = new ArrayList<>();

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }

            public List<String> getNamespaces() {
                return namespaces;
            }

            public void setNamespaces(List<String> namespaces) {
                this.namespaces = namespaces;
            }

            public List<String> getPermissions() {
                return permissions;
            }

            public void setPermissions(List<String> permissions) {
                this.permissions = permissions;
            }
        }
    }
}
