package com.x.registry.server.security;

import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server interceptor that validates Bearer tokens from the Authorization metadata key.
 */
public class AuthInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final Metadata.Key<String> AUTH_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final TokenStore tokenStore;

    public AuthInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String authHeader = headers.get(AUTH_KEY);
        String token = extractBearerToken(authHeader);

        AuthToken authToken = tokenStore.validate(token);
        if (authToken == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or missing token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        Context ctx = Context.current().withValue(SecurityContext.AUTH_TOKEN_KEY, authToken);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }
}
