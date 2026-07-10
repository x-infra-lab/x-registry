package com.x.registry.server.grpc;

import com.x.registry.server.naming.ConnectionRegistry;
import io.grpc.*;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ConnectionInterceptor implements ServerInterceptor {

    public static final Context.Key<String> CONNECTION_ID_KEY = Context.key("connection-id");
    public static final Context.Key<String> CLIENT_IP_KEY = Context.key("client-ip");
    static final Attributes.Key<String> TRANSPORT_CONNECTION_ID = Attributes.Key.create("connection-id");

    private final ConnectionRegistry connectionRegistry;

    public ConnectionInterceptor(ConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String connectionId = call.getAttributes().get(TRANSPORT_CONNECTION_ID);
        if (connectionId == null) {
            return next.startCall(call, headers);
        }

        String clientIp = extractClientIp(call);
        Context ctx = Context.current()
                .withValue(CONNECTION_ID_KEY, connectionId)
                .withValue(CLIENT_IP_KEY, clientIp);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private <ReqT, RespT> String extractClientIp(ServerCall<ReqT, RespT> call) {
        SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (remoteAddr instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return "";
    }
}
