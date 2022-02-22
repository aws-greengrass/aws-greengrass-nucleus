package com.aws.greengrass.android.provision;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class ProvisionManager {

    public static final String PROVISION_ACCESS_KEY_ID = "aws.accessKeyId";
    public static final String PROVISION_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    public static final String PROVISION_SESSION_TOKEN = "aws.sessionToken";
    public static final String PROVISION_THING_NAME = "--thing-name";
    public static final String PROVISION_USER = "component-default-user";
    public static final String PROVISION_U = "-u";

    private final Logger logger;

    public ProvisionManager() {
        logger = LogManager.getLogger(getClass());
    }

    public boolean isProvisioned() {
        String greengrassV2 = System.getProperty("root");

        boolean provisioned = false;
        // Check effectiveConfig.yaml
        try {
            Yaml yaml = new Yaml();
            InputStream inputStream = new FileInputStream(greengrassV2 + "/config/effectiveConfig.yaml");

            HashMap yamlMap = yaml.load(inputStream);
            // Access HashMaps and ArrayList by key(s)
            HashMap system = (HashMap) yamlMap.get(DeviceConfiguration.SYSTEM_NAMESPACE_KEY);
            String certPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH);
            String privKeyPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH);
            String rootCaPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH);

            if (certPath != null && privKeyPath != null && rootCaPath != null) {
                provisioned = true;
            }
        } catch (FileNotFoundException e) {
            logger.atError().setCause(e).log("Couldn't find effectiveConfig.yaml file.");
        } catch (Exception e) {
            logger.atError().setCause(e).log("Some Other Exception");
        }
        return provisioned;
    }

    public void setupSystemProperties(@NonNull JSONObject config) throws Exception {
        if (!config.has(PROVISION_ACCESS_KEY_ID)) {
            logger.atError().log("Key aws.accessKeyId is absent in the config.json.");
            throw new Exception("Parameters do not contain \"aws.accessKeyId\" key");
        } else {
            System.setProperty(PROVISION_ACCESS_KEY_ID, config.get(PROVISION_ACCESS_KEY_ID).toString());
        }
        if (!config.has(PROVISION_SECRET_ACCESS_KEY)) {
            logger.atError().log("Key aws.secretAccessKey is absent in the config.json.");
            throw new Exception("Parameters do not contain \"aws.secretAccessKey\" key");
        } else {
            System.setProperty(PROVISION_SECRET_ACCESS_KEY, config.get(PROVISION_SECRET_ACCESS_KEY).toString());
        }
        if (config.has(PROVISION_SESSION_TOKEN)) {
            System.setProperty(PROVISION_SESSION_TOKEN, config.get(PROVISION_SESSION_TOKEN).toString());
        }
    }

    public ArrayList<String> generateArgs(@NonNull JSONObject config) throws Exception {
        System.getProperty("root");
        Path path = Paths.get(System.getProperty("root"));
        FileOwnerAttributeView ownerAttributeView =
                Files.getFileAttributeView(path, FileOwnerAttributeView.class);
        String owner = ownerAttributeView.getOwner().getName();

        ArrayList<String> argsList = new ArrayList<>();
        Iterator<String> keys = config.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("-")) {
                argsList.add(key);
                if (key.equals(PROVISION_USER) || key.equals(PROVISION_U)) {
                    argsList.add(owner + ":" + owner);
                } else {
                    argsList.add(config.get(key).toString());
                }
            }
        }

        return argsList;
    }

    @Nullable
    public JSONObject parseFile(@NonNull Context context,
                                @NonNull Uri sourceUri) {
        BufferedReader reader = null;
        InputStream input;
        JSONObject config = null;
        try {
            StringBuilder builder = new StringBuilder();
            input = context.getContentResolver().openInputStream(sourceUri);
            reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            while (line != null) {
                builder.append(line).append("\n");
                line = reader.readLine();
            }
            config = new JSONObject(builder.toString());
        } catch (Throwable e) {
            logger.atError().setCause(e).log("wrong config file.");
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
        }
        return config;
    }
}
