package com.x.registry.server.security;

import io.grpc.Context;

public final class SecurityContext {

    public static final Context.Key<AuthToken> AUTH_TOKEN_KEY = Context.key("auth-token");

    private SecurityContext() {
    }
}
