package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.servicediscovery.ServiceDiscoveryAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscovery.SERVICE_DISCOVERY_NAME;
import static com.aws.iot.evergreen.util.Log.Level;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "servicediscovery", autostart = true)
public class ServiceDiscoveryService extends EvergreenService {
    private final ObjectMapper mapper = new CBORMapper();
    @Inject
    Log log;
    @Inject
    private ServiceDiscoveryAgent agent;

    @Inject
    private IPCRouter router;

    public ServiceDiscoveryService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        try {
            router.registerServiceCallback(SERVICE_DISCOVERY_NAME, this::handleMessage);
        } catch (IPCException e) {
            log.log(Level.Error, "Error registering callback for service " + SERVICE_DISCOVERY_NAME);
        }
    }

    /**
     * Handle the incoming message from the client.
     *
     * @param request the incoming request
     * @param context client request context
     * @return future containing response message
     */
    public Future<Message> handleMessage(Message request, RequestContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();
        try {
            GeneralRequest<Object, ServiceDiscoveryRequestTypes> obj =
                    IPCUtil.decode(request, new TypeReference<GeneralRequest<Object, ServiceDiscoveryRequestTypes>>() {
                    });

            GeneralResponse<?, ServiceDiscoveryResponseStatus> genResp = new GeneralResponse<>();
            switch (obj.getType()) {
                case lookup:
                    LookupResourceRequest lookup = mapper.convertValue(obj.getRequest(), LookupResourceRequest.class);
                    // Do lookup
                    genResp = agent.lookupResources(lookup, context.getServiceName());
                    break;
                case remove:
                    RemoveResourceRequest remove = mapper.convertValue(obj.getRequest(), RemoveResourceRequest.class);
                    // Do remove
                    genResp = agent.removeResource(remove, context.getServiceName());
                    break;
                case update:
                    UpdateResourceRequest update = mapper.convertValue(obj.getRequest(), UpdateResourceRequest.class);
                    // Do update
                    genResp = agent.updateResource(update, context.getServiceName());
                    break;
                case register:
                    RegisterResourceRequest register =
                            mapper.convertValue(obj.getRequest(), RegisterResourceRequest.class);
                    // Do register
                    genResp = agent.registerResource(register, context.getServiceName());
                    break;
                default:
                    genResp.setError(ServiceDiscoveryResponseStatus.InvalidRequest);
                    genResp.setErrorMessage("Unknown request type " + obj.getType());
                    break;
            }
            fut.complete(new Message(IPCUtil.encode(genResp)));
        } catch (Throwable e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);

            GeneralResponse<Void, ServiceDiscoveryResponseStatus> errorResponse =
                    GeneralResponse.<Void, ServiceDiscoveryResponseStatus>builder()
                            .error(ServiceDiscoveryResponseStatus.InternalError).errorMessage(e.getMessage()).build();

            try {
                fut.complete(new Message(IPCUtil.encode(errorResponse)));
            } catch (IOException ex) {
                log.log(Level.Error, "Couldn't even send them the error back", e);
            }
        }

        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }

        return fut;
    }
}
