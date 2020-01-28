package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.Utils;

import javax.inject.Inject;

public class AuthHandler implements InjectionActions {
    public static final String AUTH_TOKEN_LOOKUP_KEY = "_AUTH_TOKENS";

    @Inject
    private Kernel kernel;

    public String checkAuth(String token) {
        return (String) kernel.getRoot().lookup(AUTH_TOKEN_LOOKUP_KEY, token).getOnce();
    }

    public static void registerAuthToken(EvergreenService s) {
        Topic uid = s.config.createLeafChild("_UID").setParentNeedsToKnow(false);
        String authToken = Utils.generateRandomString(16).toUpperCase();
        uid.setValue(authToken);
        s.config.parent.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).setValue(s.getName());
    }
}
