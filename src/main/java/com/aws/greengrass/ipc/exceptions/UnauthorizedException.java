package com.aws.greengrass.ipc.exceptions;

public class UnauthorizedException extends IPCException {

    private static final long serialVersionUID = -7278393070938325039L;

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
