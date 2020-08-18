package com.aws.iot.evergreen.builtin.services.configstore.exceptions;

public class ValidateEventRegistrationException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public ValidateEventRegistrationException(String message) {
        super(message);
    }

    public ValidateEventRegistrationException(Throwable e) {
        super(e);
    }
}
