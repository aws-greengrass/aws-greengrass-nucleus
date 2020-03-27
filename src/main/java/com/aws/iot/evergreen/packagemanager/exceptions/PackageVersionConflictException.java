package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackageVersionConflictException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageVersionConflictException(String message) {
        super(message);
    }
}
