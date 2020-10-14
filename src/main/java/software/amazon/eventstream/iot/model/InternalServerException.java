package software.amazon.eventstream.iot.model;

public class InternalServerException extends EventStreamOperationError {
    public static final String ERROR_CODE = "aws#InternalServerException";

    public InternalServerException(String serviceName) {
        super(serviceName, ERROR_CODE, "An internal server exception has occurred.");
    }

    /**
     * Returns the named model type. May be used for a header.
     *
     * @return
     */
    @Override
    public String getApplicationModelType() {
        return ERROR_CODE;
    }
}
