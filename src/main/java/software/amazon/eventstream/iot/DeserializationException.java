package software.amazon.eventstream.iot;

public class DeserializationException extends RuntimeException {
    public DeserializationException(Object lexicalData) {
        this(lexicalData, null);
    }

    public DeserializationException(Object lexicalData, Throwable cause) {
        super("Could not deserialize data: [" + lexicalData.toString() + "]", cause);
    }
}
