package software.amazon.eventstream.iot;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Root error type returned by any continuation error message
 */
public abstract class EventStreamOperationError
        extends RuntimeException
        implements EventStreamableJsonMessage {

    @SerializedName("_service")
    @Expose(serialize = true, deserialize = true)
    private final String _service;

    @SerializedName("_message")
    @Expose(serialize = true, deserialize = true)
    private final String _message;

    @SerializedName("_errorCode")
    @Expose(serialize = true, deserialize = true)
    private final String _errorCode;

    public EventStreamOperationError(final String serviceName, final String errorCode, final String message) {
        super(String.format("%s#%s: %s", serviceName, errorCode, message));
        this._service = serviceName;
        this._errorCode = errorCode;
        this._message = message;
    }

    public String getService() {
        return _service;
    }

    /**
     * Likely overridden by a specific field defined in service-operation model
     * @return
     */
    public String getMessage() {
        return _message;
    }

    /**
     * Likely subclasses will have a more limited set of valid error codes
     * @return
     */
    public String getErrorCode() { return _errorCode; }

    /**
     * Serialize this object into a JSON payload. Does not validate object being serialized
     *
     * @param gson
     * @return
     */
    @Override
    public byte[] toPayload(Gson gson) {
        return new byte[0];
    }
}
