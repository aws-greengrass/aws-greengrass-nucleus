package com.aws.iot.evergreen.kernel.exceptions;

/**
 * Generic checked exception to be used when an invalid input is provided.
 */
public class InputValidationException extends Exception {
    public InputValidationException() {
        super();
    }

    public InputValidationException(String message) {
        super(message);
    }

    public InputValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public InputValidationException(Throwable cause) {
        super(cause);
    }
}
