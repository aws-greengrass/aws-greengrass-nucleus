package com.aws.iot.evergreen.builtin.services.configstore;

import com.aws.iot.evergreen.config.ChildChanged;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigKeyChangedEvent;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreGenericResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreReadValueRequest;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreReadValueResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.kernel.EvergreenService.CUSTOM_CONFIG_NAMESPACE;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
public class ConfigStoreIPCAgent implements InjectionActions {
    // Map from connection --> Function to call when service config changes
    private static final Map<ConnectionContext, Consumer<String>> listeners = new ConcurrentHashMap<>();
    private static final int TIMEOUT_SECONDS = 30;

    @Inject
    private Kernel kernel;

    @Inject
    private ExecutorService executor;

    private static final Logger log = LogManager.getLogger(ConfigStoreIPCAgent.class);

    private final ChildChanged onConfigChange = (whatHappened, node) -> {
        if (node == null) {
            return;
        }
        String serviceName = Kernel.findServiceForNode(node);
        // Ensure a the node that changed belongs to a service
        if (serviceName == null) {
            return;
        }

        List<String> nodePath = node.path();
        // Ensure that the node which changed was part of the custom config
        int customConfigIndex = nodePath.lastIndexOf(CUSTOM_CONFIG_NAMESPACE);
        // Compare < 1 because we want to capture only changes under the "custom" key and not the "custom" key itself
        if (customConfigIndex < 1) {
            return;
        }
        // Ensure the path is <service_name>.custom.<other stuff>
        // path is reversed, so we check serviceNameIndex - 1 == customConfigIndex
        int serviceNameIndex = nodePath.lastIndexOf(serviceName);
        if (serviceNameIndex < 0 || (serviceNameIndex - 1) != customConfigIndex) {
            return;
        }

        listeners.entrySet().stream().filter(e -> e.getKey().getServiceName().equals(serviceName))
                .map(Map.Entry::getValue).forEach(c -> c.accept(nodePath.get(customConfigIndex - 1)));
    };

    @Override
    public void postInject() {
        kernel.getConfig().getRoot().subscribe(onConfigChange);
    }

    /**
     * Handle the subscription request from the user. Immediately sends the current state to the client.
     *
     * @param context connection context
     * @return response code Success if all went well
     */
    public ConfigStoreGenericResponse subscribe(ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        listeners.computeIfAbsent(context, (key) -> {
            context.onDisconnect(() -> listeners.remove(context));
            return sendStoreUpdateToListener(context);
        });

        return new ConfigStoreGenericResponse(ConfigStoreResponseStatus.Success, null);
    }

    private Consumer<String> sendStoreUpdateToListener(ConnectionContext context) {
        return (changedKey) -> {
            ConfigKeyChangedEvent valueChangedEvent = ConfigKeyChangedEvent.builder().changedKey(changedKey).build();
            log.atDebug().log("Sending updated config key {} to {}", changedKey, context);

            try {
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(ConfigStoreImpl.API_VERSION)
                                .opCode(ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal())
                                .payload(IPCUtil.encode(valueChangedEvent)).build();
                // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut =
                        context.serverPush(BuiltInServiceDestinationCode.CONFIG_STORE.getValue(),
                                new FrameReader.Message(applicationMessage.toByteArray()));

                // call the blocking "get" in a separate thread so we don't block the publish queue
                executor.execute(() -> {
                    try {
                        fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        // Log
                        log.atError("error-sending-configstore-update").kv("context", context)
                                .log("Error sending config store update to client", e);
                    }
                });

            } catch (IOException e) {
                // Log
                log.atError("error-sending-configstore-update").kv("context", context)
                        .log("Error sending config store update to client", e);
            }
        };
    }

    /**
     * Read specified key from the service's dynamic config.
     * @param readRequest read request
     * @param context client context
     * @return response data
     */
    public ConfigStoreReadValueResponse read(ConfigStoreReadValueRequest readRequest, ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC read request");
        Topics serviceTopic = kernel.findServiceTopic(context.getServiceName());
        ConfigStoreReadValueResponse.ConfigStoreReadValueResponseBuilder response =
                ConfigStoreReadValueResponse.builder();
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Service not found")
                    .build();
        }

        Topics configTopics = serviceTopic.findInteriorChild(CUSTOM_CONFIG_NAMESPACE);
        if (configTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NoDynamicConfig)
                    .errorMessage("Service has no dynamic config").build();
        }

        Node node = configTopics.getChild(readRequest.getKey());
        if (node == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NotFound).errorMessage("Key not found").build();
        }

        response.responseStatus(ConfigStoreResponseStatus.Success);
        if (node instanceof Topic) {
            response.value(((Topic) node).getOnce());
        } else if (node instanceof Topics) {
            response.value(((Topics) node).toPOJO());
        } else {
            response.responseStatus(ConfigStoreResponseStatus.InternalError).errorMessage("Node has an unknown type");
            log.atError().log("Somehow Node has an unknown type {}", node.getClass());
        }

        return response.build();
    }
}
