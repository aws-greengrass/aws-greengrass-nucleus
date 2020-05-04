package com.aws.iot.evergreen.builtin.services.configstore;

import com.aws.iot.evergreen.config.ChildChanged;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreGenericResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigValueChangedEvent;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
public class ConfigStoreIPCAgent implements InjectionActions {
    // Map from connection --> Function to call when service config changes
    private static final Map<ConnectionContext, Consumer<Map<String, Object>>> listeners = new ConcurrentHashMap<>();
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
        String serviceName = node.findService();
        // Ensure a the node that changed belongs to a service
        if (serviceName == null) {
            return;
        }
        // Ensure that the node which changed was part of the custom config
        if (node.path().lastIndexOf(EvergreenService.CUSTOM_CONFIG_NAMESPACE) < 0) {
            return;
        }

        listeners.entrySet().stream().filter(e -> e.getKey().getServiceName().equals(serviceName))
                .map(Map.Entry::getValue).forEach(c -> c.accept(
                kernel.findServiceTopic(serviceName).createInteriorChild(EvergreenService.CUSTOM_CONFIG_NAMESPACE)
                        .toPOJO()));
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
            return sendStateUpdateToListener(context);
        });

        // Immediately send the current state
        listeners.get(context).accept(kernel.findServiceTopic(context.getServiceName())
                .createInteriorChild(EvergreenService.CUSTOM_CONFIG_NAMESPACE).toPOJO());

        return ConfigStoreGenericResponse.builder().status(ConfigStoreResponseStatus.Success).build();
    }

    private Consumer<Map<String, Object>> sendStateUpdateToListener(ConnectionContext context) {
        return (newValue) -> {
            ConfigValueChangedEvent valueChangedEvent = ConfigValueChangedEvent.builder().newValue(newValue).build();
            log.atInfo().log("Sending config {} to {}", newValue, context);

            try {
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(ConfigStoreImpl.API_VERSION)
                                .opCode(ConfigStoreServiceOpCodes.VALUE_CHANGED.ordinal())
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
}
