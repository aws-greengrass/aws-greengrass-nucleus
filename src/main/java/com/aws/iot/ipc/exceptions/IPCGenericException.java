package com.aws.iot.ipc.exceptions;

public class IPCGenericException extends Exception {
    public IPCGenericException(String message) {
        super(message);
    }

    public IPCGenericException(String message, Throwable cause) {
        super(message, cause);
    }
}