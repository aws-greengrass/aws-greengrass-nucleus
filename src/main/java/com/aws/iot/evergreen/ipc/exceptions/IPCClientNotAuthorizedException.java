package com.aws.iot.evergreen.ipc.exceptions;

public class IPCClientNotAuthorizedException extends Exception {
    public IPCClientNotAuthorizedException(String message) {
        super(message);
    }

    public IPCClientNotAuthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
