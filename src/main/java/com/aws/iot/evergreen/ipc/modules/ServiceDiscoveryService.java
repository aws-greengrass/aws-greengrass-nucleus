package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.Resource;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.ServiceDiscoveryRequestTypes;
import com.aws.iot.evergreen.ipc.services.ServiceDiscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import javax.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;

import static com.aws.iot.evergreen.ipc.services.ServiceDiscovery.ServiceDiscovery.SERVICE_DISCOVERY_NAME;
import static com.aws.iot.evergreen.util.Log.Level;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "servicediscovery", autostart = true)
public class ServiceDiscoveryService extends EvergreenService {
    private ObjectMapper mapper = new CBORMapper();

    //TODO: figure out how to inject the interface than the impl
    @Inject
    private MessageDispatcher messageDispatcher;

    @Inject
    Log log;

    public ServiceDiscoveryService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        try {
            messageDispatcher.registerServiceCallback(SERVICE_DISCOVERY_NAME, this::handleMessage);
        } catch (IPCException e) {
            log.log(Level.Error,"Error registering callback for service "+ SERVICE_DISCOVERY_NAME);
        }
    }

    public Message handleMessage(Message request) {
        // TODO: Input validation

        try {
            GeneralRequest<Object, ServiceDiscoveryRequestTypes> obj = SendAndReceiveIPCUtil.decode(request, new TypeReference<GeneralRequest<Object, ServiceDiscoveryRequestTypes>>() {});

            GeneralResponse<Object, ServiceDiscoveryResponseStatus> genResp = new GeneralResponse<>();
            switch (obj.type) {
                case lookup:
                    LookupResourceRequest lookup = mapper.convertValue(obj.request, LookupResourceRequest.class);
                    // Do lookup
                    genResp.error = ServiceDiscoveryResponseStatus.Success;
                    genResp.response = new ArrayList<Resource>();
                    break;
                case remove:
                    RemoveResourceRequest remove = mapper.convertValue(obj.request, RemoveResourceRequest.class);
                    // Do remove
                    genResp.error = ServiceDiscoveryResponseStatus.Success;
                    break;
                case update:
                    UpdateResourceRequest update = mapper.convertValue(obj.request, UpdateResourceRequest.class);
                    // Do update
                    genResp.error = ServiceDiscoveryResponseStatus.Success;
                    break;
                case register:
                    RegisterResourceRequest register = mapper.convertValue(obj.request, RegisterResourceRequest.class);
                    // Do register
                    genResp.error = ServiceDiscoveryResponseStatus.Success;
                    Resource resource = new Resource();
                    resource.name = "ABC";
                    genResp.response = resource;
                    break;
                default:
                    genResp.error = ServiceDiscoveryResponseStatus.Unknown;
                    genResp.errorMessage = "Unknown request type " + obj.type;
                    break;
            }
            return new Message(SendAndReceiveIPCUtil.encode(genResp));

        } catch (Exception e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);

            GeneralResponse<Void, ServiceDiscoveryResponseStatus> errorResponse = new GeneralResponse<>();
            errorResponse.error = ServiceDiscoveryResponseStatus.Unknown;
            errorResponse.errorMessage = e.getMessage();

            try {
                return new Message(SendAndReceiveIPCUtil.encode(errorResponse));
            } catch (IOException ex) {
                log.log(Level.Error, "Couldn't even send them the error back", e);
            }
        }
        return null;
    }
}
