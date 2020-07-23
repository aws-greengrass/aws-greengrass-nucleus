package com.aws.iot.evergreen.ipc.exceptions;

public class UnAuthenticatedException extends IPCException {
    static final long serialVersionUID = -3387516993124229948L;

    public UnAuthenticatedException(String message) {
        super(message);
    }

    public UnAuthenticatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
