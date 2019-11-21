package com.aws.iot.ipc.exceptions;

public class ConnectionIOException extends Exception {
    public ConnectionIOException(String message) {
        super(message);
    }

    public ConnectionIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
