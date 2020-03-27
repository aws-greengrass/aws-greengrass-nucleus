package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackageDownloadException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageDownloadException(String message) {
        super(message);
    }

    public PackageDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
