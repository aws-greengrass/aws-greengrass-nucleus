package software.amazon.eventstream.iot;

import software.amazon.awssdk.crt.eventstream.Header;

import java.util.List;

public class MessageAmendInfo {
    private final List<Header> headers;
    private final byte[] payload;

    public MessageAmendInfo(List<Header> headers, byte[] payload) {
        this.headers = headers;
        this.payload = payload;
    }

    public List<Header> getHeaders() {
        return headers;
    }

    public byte[] getPayload() {
        return payload;
    }
}
