package com.aws.iot.ipc.exceptions;

public class IPCClientNotAuthorizedException extends Exception {
    public IPCClientNotAuthorizedException(String message) {
        super(message);
    }

    public IPCClientNotAuthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
