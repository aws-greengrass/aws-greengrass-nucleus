package com.aws.iot.evergreen.auth.exceptions;

public class AuthZException extends Exception {
    // TODO: define better exceptions for AuthZ
    static final long serialVersionUID = -3387516993124229948L;

    public AuthZException(String message) {
        super(message);
    }

    public AuthZException(Throwable e) {
        super(e);
    }
}
