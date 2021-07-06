package com.aws.greengrass.deployment.templating;

public class IllegalTransformerException extends Exception {
    public IllegalTransformerException(String message) {
        super(message);
    }
    public IllegalTransformerException(Throwable e) {
        super(e);
    }
}
