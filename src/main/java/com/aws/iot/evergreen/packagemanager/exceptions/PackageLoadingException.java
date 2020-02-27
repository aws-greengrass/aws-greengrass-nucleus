package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackageLoadingException extends PackagingException {
    public PackageLoadingException(String message) {
        super(message);
    }

    public PackageLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
