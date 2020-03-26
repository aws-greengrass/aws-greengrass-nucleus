package com.aws.iot.evergreen.packagemanager.exceptions;

public class DefaultPlatformConfigNotFoundException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public DefaultPlatformConfigNotFoundException(String message) {
        super(message);
    }

    public DefaultPlatformConfigNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
