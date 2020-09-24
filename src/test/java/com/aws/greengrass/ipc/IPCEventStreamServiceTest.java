package com.aws.greengrass.ipc;

import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import generated.software.amazon.awssdk.iot.greengrass.GreengrassCoreIPCService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.Log;
import software.amazon.eventstream.iot.server.AuthenticationData;
import software.amazon.eventstream.iot.server.AuthenticationHandler;
import software.amazon.eventstream.iot.server.Authorization;
import software.amazon.eventstream.iot.server.AuthorizationHandler;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class IPCEventStreamServiceTest {
    private IPCEventStreamService ipcEventStreamService;
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    //    private static final String LOCAL_HOST = "127.0.0.1";
    protected static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        Log.initLoggingToFile(Log.LogLevel.Trace, "crt.log");
    }

    @Mock
    private Kernel mockKernel;

    @Mock
    private GreengrassCoreIPCService greengrassCoreIPCService;
    @Mock
    private AuthenticationHandler mockAuthenticationHandler;
    @Mock
    private AuthorizationHandler mockAuthorizationHandler;

    @BeforeEach
    public void setup() {
        when(mockAuthenticationHandler.apply(anyList(), any())).thenReturn(new AuthenticationData() {
            @Override
            public String getIdentityLabel() {
                return "Test";
            }
        });
        when(mockAuthorizationHandler.apply(any())).thenReturn(Authorization.ACCEPT);
        when(greengrassCoreIPCService.getAuthenticationHandler()).thenReturn(mockAuthenticationHandler);
        when(greengrassCoreIPCService.getAuthorizationHandler()).thenReturn(mockAuthorizationHandler);

        ipcEventStreamService = new IPCEventStreamService(mockKernel, greengrassCoreIPCService);
        ipcEventStreamService.startup();
    }

    @AfterEach
    public void tearDown() throws IOException {
        ipcEventStreamService.close();
    }

//    @SuppressWarnings({"PMD.AvoidUsingHardCodedIP", "PMD.CloseResource"})
//    @Test
//    public void testClientConnection() throws ExecutionException, InterruptedException, IOException {
//
//        Thread.sleep(3000);
//        Socket clientSocket = new Socket();
//        SocketAddress address = new InetSocketAddress(LOCAL_HOST, IPCEventStreamService.DEFAULT_PORT_NUMBER);
//        clientSocket.connect(address, 3000);
//        Header messageType = Header.createHeader(":message-type", (int) MessageType.Connect.getEnumValue());
//        Header messageFlags = Header.createHeader(":message-flags", 0);
//        Header streamId = Header.createHeader(":stream-id", 0);
//
//        List<Header> messageHeaders = new ArrayList<>(3);
//        messageHeaders.add(messageType);
//        messageHeaders.add(messageFlags);
//        messageHeaders.add(streamId);
//
//        GGEventStreamConnectMessage connectMessagePayloadStructure =
//                                GGEventStreamConnectMessage.builder().authToken("authToken").build();
//        String payload = OBJECT_MAPPER.writeValueAsString(connectMessagePayloadStructure);
//        Message connectMessage = new Message(messageHeaders, payload.getBytes(StandardCharsets.UTF_8));
//        ByteBuffer connectMessageBuf = connectMessage.getMessageBuffer();
//        byte[] toSend = new byte[connectMessageBuf.remaining()];
//        connectMessageBuf.get(toSend);
//        connectMessage.close();
//        clientSocket.getOutputStream().write(toSend);
//    }
}
