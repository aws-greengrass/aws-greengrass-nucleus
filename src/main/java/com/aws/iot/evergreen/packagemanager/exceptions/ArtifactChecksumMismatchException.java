package com.aws.iot.evergreen.packagemanager.exceptions;

public class ArtifactChecksumMismatchException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public ArtifactChecksumMismatchException(String message) {
        super(message);
    }

    public ArtifactChecksumMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
