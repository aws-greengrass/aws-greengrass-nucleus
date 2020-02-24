package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackageNotFoundException extends PackagingException {
    public PackageNotFoundException(String message) {
        super(message);
    }

    public PackageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
