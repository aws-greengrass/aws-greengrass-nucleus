package com.aws.iot.evergreen.packagemanager.exceptions;

public class UnexpectedPackagingException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public UnexpectedPackagingException(String message) {
        super(message);
    }

    public UnexpectedPackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
