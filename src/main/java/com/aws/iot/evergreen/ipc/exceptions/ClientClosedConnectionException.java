package com.aws.iot.evergreen.ipc.exceptions;

public class ClientClosedConnectionException extends ConnectionIOException {
    public ClientClosedConnectionException(String message) {
        super(message);
    }

    public ClientClosedConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

