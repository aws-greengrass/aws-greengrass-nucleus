package com.aws.iot.evergreen.ipc.modules;

import com.aws.iot.evergreen.builtin.services.servicediscovery.ServiceDiscoveryAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryGenericResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryOpCodes;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.ipc.services.servicediscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryOpCodes.values;

//TODO: see if this needs to be a GGService
@ImplementsService(name = "servicediscovery", isSystem = true)
public class ServiceDiscoveryService extends EvergreenService {
    private final ObjectMapper mapper = new CBORMapper();

    @Inject
    private ServiceDiscoveryAgent agent;

    @Inject
    private IPCRouter router;

    public ServiceDiscoveryService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        super.postInject();
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.SERVICE_DISCOVERY;
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        }
    }

    /**
     * Handle the incoming message from the client.
     *
     * @param request the incoming request
     * @param context client request context
     * @return future containing response message
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<Message> handleMessage(Message request, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();
        ApplicationMessage message = ApplicationMessage.fromBytes(request.getPayload());
        try {
            //TODO: add version compatibility check

            ServiceDiscoveryOpCodes opCode = values()[message.getOpCode()];
            ServiceDiscoveryGenericResponse response = new ServiceDiscoveryGenericResponse();
            switch (opCode) {
                case LookupResources:
                    LookupResourceRequest lookup = mapper.readValue(message.getPayload(), LookupResourceRequest.class);
                    // Do lookup
                    response = agent.lookupResources(lookup, context.getServiceName());
                    break;
                case RemoveResource:
                    RemoveResourceRequest remove = mapper.readValue(message.getPayload(), RemoveResourceRequest.class);
                    // Do remove
                    response = agent.removeResource(remove, context.getServiceName());
                    break;
                case UpdateResource:
                    UpdateResourceRequest update = mapper.readValue(message.getPayload(), UpdateResourceRequest.class);
                    // Do update
                    response = agent.updateResource(update, context.getServiceName());
                    break;
                case RegisterResource:
                    RegisterResourceRequest register =
                            mapper.readValue(message.getPayload(), RegisterResourceRequest.class);
                    // Do register
                    response = agent.registerResource(register, context.getServiceName());
                    break;
                default:
                    response.setResponseStatus(ServiceDiscoveryResponseStatus.InvalidRequest);
                    response.setErrorMessage("Unknown request type " + opCode.toString());
                    break;
            }
            ApplicationMessage applicationMessage = ApplicationMessage.builder().version(message.getVersion())
                    .payload(mapper.writeValueAsBytes(response)).build();
            fut.complete(new Message(applicationMessage.toByteArray()));
        } catch (Throwable e) {
            logger.atError().setEventType("service-discovery-error").setCause(e).log("Failed to handle message");
            try {
                ServiceDiscoveryGenericResponse response =
                        new ServiceDiscoveryGenericResponse(ServiceDiscoveryResponseStatus.InternalError,
                                e.getMessage());
                ApplicationMessage responseMessage = ApplicationMessage.builder().version(message.getVersion())
                        .payload(mapper.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError().setEventType("service-discovery-error").setCause(ex)
                        .log("Failed to send error response");
            }
        }

        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }
}
