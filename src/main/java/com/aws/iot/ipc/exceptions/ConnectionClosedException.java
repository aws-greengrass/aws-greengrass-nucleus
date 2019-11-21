package com.aws.iot.ipc.exceptions;

public class ConnectionClosedException extends Exception {
    public ConnectionClosedException(String message) {
        super(message);
    }

    public ConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
