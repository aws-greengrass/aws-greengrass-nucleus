/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Basic implementation of ProvisionManager interface.
 */
public class BaseProvisionManager implements ProvisionManager {

    public static final String PROVISION_THING_NAME = "--thing-name";
    public static final String KERNEL_INIT_CONFIG_ARG = "--init-config";
    public static final String THING_NAME_CHECKER = "[a-zA-Z0-9:_-]+";

    private static final String PROVISION_ACCESS_KEY_ID = "aws.accessKeyId";
    private static final String PROVISION_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    private static final String PROVISION_SESSION_TOKEN = "aws.sessionToken";
    private static final String PRIV_KEY_FILE = "privKey.key";
    private static final String ROOT_CA_FILE = "rootCA.pem";
    private static final String THING_CERT_FILE = "thingCert.crt";
    private static final String EFFECTIVE_CONFIG_FILE = "effectiveConfig.yaml";
    private static final String CONFIG_FOLDER = "config";
    private static final String ROOT_FOLDER = "/greengrass/v2";

    private final Logger logger;

    public BaseProvisionManager() {
        logger = LogManager.getLogger(getClass());
    }

    /**
     * Checking if the nucleus is ready to run.
     *
     * @param context application Context
     * @return result of checking
     */
    @Override
    public boolean isProvisioned(@NonNull Context context) {
        boolean provisioned = false;
        // Check effectiveConfig.yaml
        try (InputStream inputStream =
                     Files.newInputStream(Paths.get(String.format("%s/%s/%s", getRoot(context),
                             CONFIG_FOLDER, EFFECTIVE_CONFIG_FILE)))) {
            Yaml yaml = new Yaml();
            HashMap yamlMap = yaml.load(inputStream);
            // Access HashMaps and ArrayList by key(s)
            HashMap system = (HashMap) yamlMap.get(DeviceConfiguration.SYSTEM_NAMESPACE_KEY);
            String certPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH);
            String privKeyPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH);
            String rootCaPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH);

            if (!TextUtils.isEmpty(certPath)
                    && !TextUtils.isEmpty(privKeyPath)
                    && !TextUtils.isEmpty(rootCaPath)) {
                provisioned = true;
            }
        } catch (FileNotFoundException e) {
            logger.atError().setCause(e).log(String.format("Couldn't find \"%s\" file.", EFFECTIVE_CONFIG_FILE));
        } catch (Exception e) {
            logger.atError()
                    .setCause(e)
                    .log(String.format("\"An error occurred during parsing \"%s\"", EFFECTIVE_CONFIG_FILE));
        }
        return provisioned;
    }

    /**
     * Reset nucleus settings.
     *
     * @param context application Context
     */
    @Override
    public void clearProvision(@NonNull Context context) {
        String root = getRoot(context);
        deleteRecursive(new File(String.format("%s/%s", root, CONFIG_FOLDER)));
        new File(String.format("%s/%s", root, PRIV_KEY_FILE)).delete();
        new File(String.format("%s/%s", root, ROOT_CA_FILE)).delete();
        new File(String.format("%s/%s", root, THING_CERT_FILE)).delete();
    }

    /**
     * Setup SystemProperties.
     *
     * @param config settings for provisioning
     * @throws Exception if the config is not valid
     */
    @Override
    public void setupSystemProperties(@NonNull JsonNode config) throws Exception {
        if (!config.has(PROVISION_ACCESS_KEY_ID)) {
            logger.atError().log(String.format("Key \"%s\" is absent in the config file.", PROVISION_ACCESS_KEY_ID));
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_ACCESS_KEY_ID));
        } else {
            System.setProperty(PROVISION_ACCESS_KEY_ID, config.get(PROVISION_ACCESS_KEY_ID).asText());
        }
        if (!config.has(PROVISION_SECRET_ACCESS_KEY)) {
            logger.atError()
                    .log(String.format("Key \"%s\" is absent in the config file.", PROVISION_SECRET_ACCESS_KEY));
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_SECRET_ACCESS_KEY));
        } else {
            System.setProperty(PROVISION_SECRET_ACCESS_KEY, config.get(PROVISION_SECRET_ACCESS_KEY).asText());
        }
        if (config.has(PROVISION_SESSION_TOKEN)) {
            System.setProperty(PROVISION_SESSION_TOKEN, config.get(PROVISION_SESSION_TOKEN).asText());
        }
    }

    /**
     * Clear SystemProperties.
     */
    @Override
    public void clearSystemProperties() {
        System.clearProperty(PROVISION_ACCESS_KEY_ID);
        System.clearProperty(PROVISION_SECRET_ACCESS_KEY);
        System.clearProperty(PROVISION_SESSION_TOKEN);
    }

    /**
     * Create args to run nucleus.
     *
     * @param config settings for provisioning
     * @return list of args
     */
    @NonNull
    @Override
    public ArrayList<String> generateArgs(@NonNull JsonNode config) {
        ArrayList<String> argsList = new ArrayList<>();
        Iterator<String> keys = config.fieldNames();

        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("-")) {
                argsList.add(key);
                argsList.add(config.get(key).asText());
            }
        }

        return argsList;
    }

    /**
     * Parsing a file for provisioning.
     *
     * @param context application Context
     * @param sourceUri path to file
     * @return JsonNode
     */
    @Nullable
    public JsonNode parseFile(@NonNull Context context,
                              @NonNull Uri sourceUri) {
        JsonNode config = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        context.getContentResolver().openInputStream(sourceUri), StandardCharsets.UTF_8)
        )) {
            StringBuilder builder = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                builder.append(line).append("\n");
                line = reader.readLine();
            }

            ObjectMapper mapper = new ObjectMapper();
            config = mapper.readTree(builder.toString());

        } catch (Throwable e) {
            logger.atError().setCause(e).log("wrong config file.");
        }
        return config;
    }

    private void deleteRecursive(@NonNull File fileOrDirectory) {
        File[] list;
        if (fileOrDirectory.isDirectory()) {
            list = fileOrDirectory.listFiles();
            if (list != null) {
                for (File child : list) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private String getRoot(@NonNull Context context) {
        return context.getFilesDir() + ROOT_FOLDER;
    }
}
