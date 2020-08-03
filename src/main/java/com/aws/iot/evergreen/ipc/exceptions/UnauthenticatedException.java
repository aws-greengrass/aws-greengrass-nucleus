package com.aws.iot.evergreen.ipc.exceptions;

public class UnauthenticatedException extends IPCException {
    static final long serialVersionUID = -3387516993124229948L;

    public UnauthenticatedException(String message) {
        super(message);
    }

    public UnauthenticatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
