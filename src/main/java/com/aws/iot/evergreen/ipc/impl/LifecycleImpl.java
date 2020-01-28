/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.impl;

import com.aws.iot.evergreen.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.iot.evergreen.ipc.Ipc;
import com.aws.iot.evergreen.ipc.LifecycleGrpc;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.exceptions.LifecycleIPCException;
import io.grpc.stub.StreamObserver;


public class LifecycleImpl extends LifecycleGrpc.LifecycleImplBase {
    private final LifecycleIPCAgent agent;

    public LifecycleImpl(LifecycleIPCAgent agent) {
        this.agent = agent;
    }

    @Override
    public void requestStateChange(Ipc.StateChangeRequest request, StreamObserver<Ipc.StateChangeResponse> responseObserver) {
        GeneralResponse<Void, LifecycleResponseStatus> resp = agent.setState(request, (String) AuthInterceptor.USER_IDENTITY.get());

        if (resp.getError().equals(LifecycleResponseStatus.Success)) {
            responseObserver.onNext(Ipc.StateChangeResponse.newBuilder().build());
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(new LifecycleIPCException(resp.getErrorMessage()));
        }
    }

    @Override
    public void listenToStateChanges(Ipc.StateChangeListenRequest request, StreamObserver<Ipc.StateTransition> responseObserver) {
        agent.listen(request, responseObserver, (String) AuthInterceptor.USER_IDENTITY.get());
        synchronized (responseObserver) {
            // Send a response when the subscription is done
            responseObserver.onNext(Ipc.StateTransition.newBuilder().build());
        }
    }
}
