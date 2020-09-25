package com.aws.greengrass.componentmanager.exceptions;

public class SizeLimitException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public SizeLimitException(String message) {
        super(message);
    }

    public SizeLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
