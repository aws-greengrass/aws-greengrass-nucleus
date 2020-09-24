//package com.aws.greengrass.ipc;
//
//import com.aws.greengrass.ipc.common.GGEventStreamConnectMessage;
//import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
//import com.aws.greengrass.logging.api.Logger;
//import com.aws.greengrass.logging.impl.LogManager;
//import com.fasterxml.jackson.databind.DeserializationFeature;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.NonNull;
//import org.apache.commons.lang3.StringUtils;
//import software.amazon.awssdk.crt.eventstream.ServerConnection;
//import generated.software.amazon.awssdk.iot.greengrass.GreengrassCoreIPCService;
//
//import java.io.IOException;
//import java.util.concurrent.ConcurrentHashMap;
//import javax.inject.Inject;
//
//public class GreengrassOperationContinuationHandlerFactory extends GreengrassCoreIPCService {
//
//    private static final ObjectMapper OBJECT_MAPPER =
//            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
//                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
//
//    private Logger logger = LogManager.getLogger(GreengrassOperationContinuationHandlerFactory.class);
//
//    @Inject
//    AuthenticationHandler authenticationHandler;
//
//    ConcurrentHashMap<Long, String> serverConnectionToServiceName;
//
//    public GreengrassOperationContinuationHandlerFactory() {
//        super();
//        serverConnectionToServiceName = new ConcurrentHashMap<>();
//    }
//
//    @Override
//    public boolean authenticate(byte[] payload, @NonNull ServerConnection connection) {
//        String authToken = null;
//        System.out.println("Authenticating IPC event stream client");
//        try {
//            GGEventStreamConnectMessage connectMessage = OBJECT_MAPPER.readValue(payload,
//                    GGEventStreamConnectMessage.class);
//            authToken = connectMessage.getAuthToken();
//        } catch (IOException e) {
//            logger.atError().log("Invalid auth token in connect message");
//            return false;
//        }
//        if (StringUtils.isEmpty(authToken)) {
//            logger.atError().log("Received empty auth token to authenticate IPC client");
//            return false;
//        }
//        try {
//            String serviceName = authenticationHandler.doAuthentication(authToken);
//            serverConnectionToServiceName.put(connection.getNativeHandle(), serviceName);
//            return true;
//        } catch (UnauthenticatedException e) {
//            return false;
//        }
//    }
//
//    public String getServiceForConnection(@NonNull ServerConnection connection) {
//        return serverConnectionToServiceName.get(connection.getNativeHandle());
//    }
//}
