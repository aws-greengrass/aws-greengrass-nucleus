package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.ipc.common.Server;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.ConnectionManager;
import com.aws.iot.evergreen.util.Log;

import javax.inject.Inject;

import static com.aws.iot.evergreen.util.Log.*;


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
 *    - Register call backs for an opcode
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
 *  dispatcher looks up the call back based on the request opcode and invokes the call back. The result of the
 *  call back is sent out via the same connection.
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

    //TODO: figure out how to inject the interface ConnectionManager
    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private Server server;

    @Inject
    Log log;

    public IPCService(Topics c) {
        super(c);
    }

    /**
     * server.startup() binds the server socket to localhost:port. That information is
     * pushed to the IPCService config store
     */
    @Override
    public void startup() {
        log.log(Level.Note, "Startup called for IPC service");
        try {
            server.startup();
            super.startup();
        } catch (IPCException e) {
            log.error("Error starting IPC service", e);
//            setState(State.Unstable);    Unstable got deleted, was that wrong?
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
        try {
            server.run();
        } catch (IPCException e) {
            log.error("IPC service run() errored", e);
//            setState(State.Unstable);
            recover();
        }
    }

    private void recover(){
        try{
            //TODO: rebind server to same address:port.
            //TODO: set state to running if able to recover, is this the best way to do state transitions
        }catch (Exception e){
            //TODO: Failed to rebind server, report status as error to kernel
            setState(State.Errored);
            //
        }
    }
    /**
     *
     */
    @Override
    public void shutdown() {
        log.log(Level.Note, "Shutdown called for IPC service");
        //TODO: transition to errored state if shutdown failed ?
        server.shutdown();
        connectionManager.shutdown();
    }

    /**
     * IPCService will only transition to errored state if the server socket is not able to bind or accept connections
     *
     */
    @Override
    public void handleError() {

    }
}
