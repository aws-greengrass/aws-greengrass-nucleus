package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackagingException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public PackagingException(String message) {
        super(message);
    }

    public PackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
