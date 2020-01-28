/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.impl;

import com.aws.iot.evergreen.ipc.handler.AuthHandler;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

public class AuthInterceptor implements ServerInterceptor {
    public static final Context.Key<Object> USER_IDENTITY
            = Context.key("identity");

    private final AuthHandler auth;

    public AuthInterceptor(AuthHandler auth) {
        this.auth = auth;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String authHeader = headers.get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER));
        if (authHeader == null) {
            authHeader = "";
        }

        if (!(authHeader.startsWith("Bearer ") || authHeader.startsWith("bearer "))) {
            return next.startCall(call, headers);
        }

        String token = authHeader.substring("Bearer ".length());
        String serviceName = auth.checkAuth(token);

        if (serviceName == null) {
            call.close(Status.UNAUTHENTICATED, new Metadata());
        }

        Context context = Context.current().withValue(USER_IDENTITY, serviceName);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
