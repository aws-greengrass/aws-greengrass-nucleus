package com.aws.iot.evergreen.ipc.exceptions;

public class IPCClientNotAuthorizedException extends IPCException {
    static final long serialVersionUID = -3387516993124229948L;

    public IPCClientNotAuthorizedException(String message) {
        super(message);
    }

    public IPCClientNotAuthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
