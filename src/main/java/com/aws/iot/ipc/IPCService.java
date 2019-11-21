package com.aws.iot.ipc;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.dependency.State;
import com.aws.iot.gg2k.GGService;
import com.aws.iot.ipc.common.Server;
import com.aws.iot.ipc.exceptions.IPCGenericException;
import com.aws.iot.ipc.handler.DefaultHandlerImpl;


import javax.inject.Inject;

import static com.aws.iot.util.Log.*;

/**
 *  IPCService manages the life-cycle of Server and eventsHandler.
 *
 *  Server:
 *  Listens for incoming client connections, forwards accepted connection to eventsHandler
 *
 *  ConnectionImpl:
 *  Represents a long lived connection with an external process. ConnectionImpl's life cycle is managed by eventsHandler
 *
 *  HandlerImpl (implements event-handler and message-handler)
 *  Handles all events related to a connection. Glue between IPC components and other services
 *  that need to talk to external process.
 *
 */

@ImplementsService(name = "IPCService", autostart = true)
public class IPCService extends GGService {

    //TODO: figure out how to inject the interface EventHandler
    @Inject
    private DefaultHandlerImpl eventHandler;

    @Inject
    private Server server;

    public IPCService(Topics c) {
        super(c);
    }

    @Override
    public void startup() {
        log().log(Level.Note, "Startup called for IPC service");
        server.startup();
        //TODO: propagate server information to external process via env variables
        server.getServerInfo().forEach((key,value) -> config.lookup(key).setValue(value));
        super.startup();
    }

    @Override
    public void run() {
        log().log(Level.Note, "Run called for IPC service");
        try {
            server.run();
        } catch (IPCGenericException e) {
            setState(State.Errored);
        }
    }

    @Override
    public void shutdown() {
        log().log(Level.Note, "Shutdown called for IPC service");
        //TODO: transition to errored state if shutdown failed ?
        server.shutdown();
        eventHandler.shutdown();
    }
}
