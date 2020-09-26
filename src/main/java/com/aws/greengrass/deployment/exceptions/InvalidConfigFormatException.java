package com.aws.greengrass.deployment.exceptions;

public class InvalidConfigFormatException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidConfigFormatException(String message) {
        super(message);
    }

    public InvalidConfigFormatException(Throwable e) {
        super(e);
    }
}
