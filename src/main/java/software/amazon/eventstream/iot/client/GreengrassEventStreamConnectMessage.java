package software.amazon.eventstream.iot.client;

public class GreengrassEventStreamConnectMessage {

    private String authToken;

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getAuthToken() {
        return this.authToken;
    }
}
