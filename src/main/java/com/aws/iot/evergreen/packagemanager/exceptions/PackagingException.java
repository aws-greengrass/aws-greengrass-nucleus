package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackagingException extends Exception {
   public PackagingException(String message) {
        super(message);
    }

    public PackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}