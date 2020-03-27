package com.aws.iot.evergreen.packagemanager.exceptions;

public class DirectoryCreationFailedForPackageException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public DirectoryCreationFailedForPackageException(String message) {
        super(message);
    }

    public DirectoryCreationFailedForPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
