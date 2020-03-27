package com.aws.iot.evergreen.packagemanager.exceptions;

public class UnsupportedRecipeFormatException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public UnsupportedRecipeFormatException(String message) {
        super(message);
    }

    public UnsupportedRecipeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
