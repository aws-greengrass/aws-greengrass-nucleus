package com.aws.iot.evergreen.packagemanager.exceptions;

public class UnexpectedPackagingException extends PackagingException {
    public UnexpectedPackagingException(String message) {
        super(message);
    }

    public UnexpectedPackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
