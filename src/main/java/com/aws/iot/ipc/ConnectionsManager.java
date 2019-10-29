package com.aws.iot.ipc;

import java.util.concurrent.ConcurrentHashMap;

import static com.aws.iot.ipc.IPCServer.*;

public class ConnectionsManager {

    private final ConcurrentHashMap<String, GGServiceConnection> connectedClients = new ConcurrentHashMap<>();

    public void addConnection(GGServiceConnection connection){
        GGServiceConnection ggServiceConnection = connectedClients.putIfAbsent(connection.clientId, connection);
        if(ggServiceConnection != null ){
            throw new IllegalStateException("Client already registered");
        }
    }

    public void removeConnection(String clientId){
        connectedClients.remove(clientId);
    }

    public void shutdown(){
        connectedClients.values().forEach( c -> c.close());
    }

    public ClientConnection getConnection(String clientId){
        return connectedClients.get(clientId);
    }

}
