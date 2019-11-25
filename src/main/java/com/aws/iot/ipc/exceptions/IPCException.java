package com.aws.iot.ipc.exceptions;

public class IPCException extends Exception {
    public IPCException(String message) {
        super(message);
    }

    public IPCException(String message, Throwable cause) {
        super(message, cause);
    }
}