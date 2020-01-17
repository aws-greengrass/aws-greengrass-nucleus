package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.servicediscovery.ServiceDiscoveryAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryRequestTypes;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.ipc.services.servicediscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import javax.inject.Inject;
import java.io.IOException;

import static com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscovery.SERVICE_DISCOVERY_NAME;
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

    @Inject
    private ServiceDiscoveryAgent agent;

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

    public Message handleMessage(Message request, RequestContext context) {
        try {
            GeneralRequest<Object, ServiceDiscoveryRequestTypes> obj = SendAndReceiveIPCUtil.decode(request, new TypeReference<GeneralRequest<Object, ServiceDiscoveryRequestTypes>>() {});

            GeneralResponse<?, ServiceDiscoveryResponseStatus> genResp = new GeneralResponse<>();
            switch (obj.getType()) {
                case lookup:
                    LookupResourceRequest lookup = mapper.convertValue(obj.getRequest(), LookupResourceRequest.class);
                    // Do lookup
                    genResp = agent.lookupResources(lookup, context.serviceName);
                    break;
                case remove:
                    RemoveResourceRequest remove = mapper.convertValue(obj.getRequest(), RemoveResourceRequest.class);
                    // Do remove
                    genResp = agent.removeResource(remove, context.serviceName);
                    break;
                case update:
                    UpdateResourceRequest update = mapper.convertValue(obj.getRequest(), UpdateResourceRequest.class);
                    // Do update
                    genResp = agent.updateResource(update, context.serviceName);
                    break;
                case register:
                    RegisterResourceRequest register = mapper.convertValue(obj.getRequest(), RegisterResourceRequest.class);
                    // Do register
                    genResp = agent.registerResource(register, context.serviceName);
                    break;
                default:
                    genResp.setError(ServiceDiscoveryResponseStatus.Unknown);
                    genResp.setErrorMessage("Unknown request type " + obj.getType());
                    break;
            }
            return new Message(SendAndReceiveIPCUtil.encode(genResp));

        } catch (Throwable e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);

            GeneralResponse<Void, ServiceDiscoveryResponseStatus> errorResponse =
                    GeneralResponse.<Void, ServiceDiscoveryResponseStatus>builder()
                            .error(ServiceDiscoveryResponseStatus.Unknown)
                            .errorMessage(e.getMessage()).build();

            try {
                return new Message(SendAndReceiveIPCUtil.encode(errorResponse));
            } catch (IOException ex) {
                log.log(Level.Error, "Couldn't even send them the error back", e);
            }
        }
        return null;
    }
}
