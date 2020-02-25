package com.aws.iot.evergreen.packagemanager.exceptions;

public class DefaultPlatformConfigNotFoundException extends PackagingException {
    public DefaultPlatformConfigNotFoundException(String message) {
        super(message);
    }

    public DefaultPlatformConfigNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
