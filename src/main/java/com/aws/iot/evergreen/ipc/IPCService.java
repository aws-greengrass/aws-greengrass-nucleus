package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.handler.AuthHandler;
import com.aws.iot.evergreen.ipc.impl.AuthInterceptor;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.Log;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.util.MutableHandlerRegistry;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.util.Log.Level;


/**
 *  Entry point to the kernel service IPC mechanism. IPC service manages the lifecycle of all IPC components
 *
 *  Server:
 *  Listens for new connections and passes new connections to connection manager
 *
 *  Connection Manager:
 *  Manages connections created by Server, connection manager is responsible for
 *  - Authenticating a new connection
 *  - Creating readers and writers for a connection
 *  - Closes connection when read or write message has IOErrors
 *  - Closes connection during shutdown
 *
 *  ConnectionReader:
 *  - Reads messages from the connection input stream and forwards them to message dispatcher
 *  - Forwards IOErrors to connection manager
 *
 *  ConnectionWriter:
 *  - Writes messages to the connection output stream
 *  - Forwards IOErrors to connection manager
 *
 *  Message Dispatcher:
 *  - Handles incoming messages from connections
 *  - Acts as an interface for modules inside the kernel to
 *    - Register call backs for a destination
 *    - Send messages to an outside process
 *  - Manages the thread pool which process all incoming and outgoing messages
 *
 *  IPCService relies on the kernel to synchronize between startup() and run() calls.
 *
 *  How messages flow:
 *
 *  New connection:
 *  Server listens for new connections, new connections are forwarded to connection manager.
 *  Connection manager authorizes connection and creates connection reader and writer
 *
 *  Incoming message from an external process
 *  Connection reader is run on a separate thread which does the blocking read on connection input stream,
 *  Message read by connection reader is forwarded to connection dispatcher, if the message is a new request,
 *  dispatcher looks up the call back based on the request destination and invokes the callback. The result of the
 *  callback is sent out via the same connection.
 *  If the message is a response to a previous request, dispatcher looks up the future object associated with the
 *  request using the sequence number and updates the future.
 *
 *  Outgoing messages:
 *  Modules in the kernel that need to send messages to an external process would inject into itself a
 *  reference of the message dispatcher. Module can send message using client Id of the process via
 *  dispatcher. Dispatcher will look up the connection associated with the clientId using connection manager
 *  and write the message to the connection. Dispatcher will return a future object to the module which
 *  will be marked as complete when connection responds to the message.
 */

@ImplementsService(name = "IPCService", autostart = true)
public class IPCService extends EvergreenService {
    public static final String KERNEL_URI_ENV_VARIABLE_NAME = "AWS_GG_KERNEL_URI";
    private final io.grpc.Server grpcServer;
    private static final MutableHandlerRegistry registry = new MutableHandlerRegistry();

    @Inject
    private static AuthHandler auth;

    @Inject
    Log log;
    @Inject
    private Kernel kernel;
    private Server server;

    public IPCService(Topics c) {
        super(c);
        grpcServer = NettyServerBuilder.forPort(0)
                .fallbackHandlerRegistry(registry)
                .permitKeepAliveWithoutCalls(true)
                .maxConcurrentCallsPerConnection(6) // Not chosen for any particular reason
                .build();
    }

    public static void registerService(BindableService service) {
        registry.addService(ServerInterceptors.intercept(service, new AuthInterceptor(auth)));
    }

    /**
     * server.startup() binds the server socket to localhost:port. That information is
     * pushed to the IPCService config store
     */
    @Override
    public void startup() {
        log.log(Level.Note, "Startup called for IPC service");
        try {
            server = grpcServer.start();
            kernel.getRoot().lookup("setenv", KERNEL_URI_ENV_VARIABLE_NAME).setValue("tcp://127.0.0.1:" + server.getPort());
            super.startup();
        } catch (IOException e) {
            log.error("Error starting IPC service", e);
            setState(State.Errored);
            recover();
        }
    }
    /**
     * Blocks indefinitely listening for new connection. If the server socket errors while listening, the exception
     * is bubbled up and IPCService will transition to Errored state.
     */
    @Override
    public void run() {
        log.log(Level.Note, "Run called for IPC service");
    }

    private void recover(){
        try{
            //TODO: rebind server to same address:port.
            //TODO: set state to running if able to recover, is this the best way to do state transitions
        }catch (Exception e){
            //TODO: Failed to rebind server, report status as error to kernel
            setState(State.Errored);
        }
    }
    /**
     *
     */
    @Override
    public void shutdown() {
        log.log(Level.Note, "Shutdown called for IPC service");
        try {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * IPCService will only transition to errored state if the server socket is not able to bind or accept connections
     *
     */
    @Override
    public void handleError() {

    }
}
