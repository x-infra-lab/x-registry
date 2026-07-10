package com.x.registry.server.security;

import io.grpc.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    private static final Metadata.Key<String> AUTH_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private TokenStore tokenStore;

    @Mock
    private ServerCall<Object, Object> serverCall;

    @Mock
    private ServerCallHandler<Object, Object> next;

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(tokenStore);
    }

    @Test
    void validBearerTokenAllowsCallToProceeed() {
        AuthToken authToken = new AuthToken("id1", "valid-secret", Set.of("*"), Set.of(Permission.READ));
        when(tokenStore.validate("valid-secret")).thenReturn(authToken);

        ServerCall.Listener<Object> expectedListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(), any())).thenReturn(expectedListener);

        Metadata headers = new Metadata();
        headers.put(AUTH_KEY, "Bearer valid-secret");

        ServerCall.Listener<Object> result = interceptor.interceptCall(serverCall, headers, next);

        verify(tokenStore).validate("valid-secret");
        verify(next).startCall(any(), any());
        verify(serverCall, never()).close(any(), any());
        assertNotNull(result);
    }

    @Test
    void missingAuthorizationHeaderReturnsUnauthenticated() {
        when(tokenStore.validate(null)).thenReturn(null);

        Metadata headers = new Metadata();

        interceptor.interceptCall(serverCall, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.UNAUTHENTICATED.getCode(), statusCaptor.getValue().getCode());
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void invalidTokenReturnsUnauthenticated() {
        when(tokenStore.validate("invalid-token")).thenReturn(null);

        Metadata headers = new Metadata();
        headers.put(AUTH_KEY, "Bearer invalid-token");

        interceptor.interceptCall(serverCall, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.UNAUTHENTICATED.getCode(), statusCaptor.getValue().getCode());
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void malformedHeaderNotBearerReturnsUnauthenticated() {
        when(tokenStore.validate(null)).thenReturn(null);

        Metadata headers = new Metadata();
        headers.put(AUTH_KEY, "Basic some-credentials");

        interceptor.interceptCall(serverCall, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.UNAUTHENTICATED.getCode(), statusCaptor.getValue().getCode());
        verify(next, never()).startCall(any(), any());
    }
}
