package software.amazon.awssdk.eventstreamrpc.model;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;

/**
 * All generated model types implement this interface, including errors.
 */
public interface EventStreamJsonMessage {
    /**
     * Serialize this object into a JSON payload. Does not validate object being serialized
     *
     * @param gson
     * @return
     */
    default byte[] toPayload(final Gson gson) {
        return gson.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    default EventStreamJsonMessage fromJson(final Gson gson, byte[] payload) {
        final String payloadString = new String(payload, StandardCharsets.UTF_8);
        if (payloadString.equals("null")) {
            gson.fromJson("{}", this.getClass());
        }
        return gson.fromJson(payloadString, this.getClass());
    }

    /**
     * If anything needs to be done in memory after parsing from JSON, override and perform it here
     */
    default void postFromJson() { }

    /**
     * Returns the named model type. May be used for a header.
     * @return
     */
    public String getApplicationModelType();

    default boolean isVoid() { return false; }
}
