package com.aws.iot.evergreen.packagemanager.exceptions;

public class DirectoryCreationFailedForPackageException extends PackagingException {
    public DirectoryCreationFailedForPackageException(String message) {
        super(message);
    }

    public DirectoryCreationFailedForPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
