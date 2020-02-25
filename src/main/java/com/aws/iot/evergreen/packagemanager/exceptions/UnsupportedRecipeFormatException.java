package com.aws.iot.evergreen.packagemanager.exceptions;

public class UnsupportedRecipeFormatException extends PackagingException {
    public UnsupportedRecipeFormatException(String message) {
        super(message);
    }

    public UnsupportedRecipeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
