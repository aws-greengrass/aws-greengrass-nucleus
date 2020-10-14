package software.amazon.eventstream.iot;

import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

/**
 * Unmapped data exception is generated either on server or client side when recieving data
 * over the wire and is unable to map properly to an expected type to be received
 * for the operation. Or an exception (don't have to be called out).
 */
public class UnmappedDataException extends RuntimeException {
    public UnmappedDataException(String applicationModelType) {
        super(String.format("Cannot find Java class type for application model type: %s", applicationModelType));
    }

    public UnmappedDataException(Class<? extends EventStreamJsonMessage> expectedClass) {
        super(String.format("Data does not map into Java class: %s", expectedClass.getCanonicalName()));
    }
}
