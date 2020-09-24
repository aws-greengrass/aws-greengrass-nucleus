package software.amazon.eventstream.iot;

import com.google.gson.Gson;

/**
 * All generated model types implement this interface, including errors.
 */
public interface EventStreamableJsonMessage {
    /**
     * Serialize this object into a JSON payload. Does not validate object being serialized
     *
     * @param gson
     * @return
     */
    default public byte[] toPayload(final Gson gson) {
        return gson.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Returns the named model type. May be used for a header.
     * @return
     */
    public String getApplicationModelType();

    default public boolean isVoid() { return false; }
}
