/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.impl;

import com.aws.iot.evergreen.builtin.services.servicediscovery.ServiceDiscoveryAgent;
import com.aws.iot.evergreen.ipc.Ipc;
import com.aws.iot.evergreen.ipc.ServiceDiscoveryGrpc;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;


public class ServiceDiscoveryImpl extends ServiceDiscoveryGrpc.ServiceDiscoveryImplBase {
    private final ServiceDiscoveryAgent agent;

    public ServiceDiscoveryImpl(ServiceDiscoveryAgent serviceDiscoveryAgent) {
        this.agent = serviceDiscoveryAgent;
    }

    @Override
    public void registerResource(Ipc.RegisterResourceRequest request, StreamObserver<Ipc.Resource> responseObserver) {
        GeneralResponse<Ipc.Resource, ServiceDiscoveryResponseStatus> res = agent.registerResource(request, (String) AuthInterceptor.USER_IDENTITY.get());

        if (res.getError().equals(ServiceDiscoveryResponseStatus.Success)) {
            responseObserver.onNext(request.getResource());
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(Status.ABORTED.withDescription(res.getErrorMessage()).asRuntimeException());
        }
    }

    @Override
    public void lookupResources(Ipc.LookupResourcesRequest request, StreamObserver<Ipc.Resource> responseObserver) {
        GeneralResponse<List<Ipc.Resource>, ServiceDiscoveryResponseStatus> res = agent.lookupResources(request, (String) AuthInterceptor.USER_IDENTITY.get());

        if (res.getError().equals(ServiceDiscoveryResponseStatus.Success)) {
            res.getResponse().forEach(responseObserver::onNext);
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(Status.ABORTED.withDescription(res.getErrorMessage()).asRuntimeException());
        }
    }

    @Override
    public void removeResource(Ipc.RemoveResourceRequest request, StreamObserver<Ipc.Resource> responseObserver) {
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> res = agent.removeResource(request, (String) AuthInterceptor.USER_IDENTITY.get());

        if (res.getError().equals(ServiceDiscoveryResponseStatus.Success)) {
            responseObserver.onNext(request.getResource());
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(Status.ABORTED.withDescription(res.getErrorMessage()).asRuntimeException());
        }
    }

    @Override
    public void updateResource(Ipc.UpdateResourceRequest request, StreamObserver<Ipc.Resource> responseObserver) {
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> res = agent.updateResource(request, (String) AuthInterceptor.USER_IDENTITY.get());

        if (res.getError().equals(ServiceDiscoveryResponseStatus.Success)) {
            responseObserver.onNext(request.getResource());
            responseObserver.onCompleted();
        } else {
            responseObserver.onError(Status.ABORTED.withDescription(res.getErrorMessage()).asRuntimeException());
        }
    }
}
