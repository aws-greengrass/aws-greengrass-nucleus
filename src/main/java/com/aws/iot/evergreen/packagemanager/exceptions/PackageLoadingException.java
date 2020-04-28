package com.aws.iot.evergreen.packagemanager.exceptions;

// TODO Think about refactoring this to PackageIOException
public class PackageLoadingException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageLoadingException(String message) {
        super(message);
    }

    public PackageLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
