/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Basic implementation of ProvisionManager interface.
 */
public class BaseProvisionManager implements ProvisionManager {

    public static final String PROVISION_THING_NAME = "--thing-name";
    public static final String THING_NAME_CHECKER = "[a-zA-Z0-9:_-]+";

    public static final String PROVISION_ACCESS_KEY_ID = "aws.accessKeyId";
    public static final String PROVISION_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    public static final String PROVISION_SESSION_TOKEN = "aws.sessionToken";
    private static final String PRIV_KEY_FILE = "privKey.key";
    private static final String ROOT_CA_FILE = "rootCA.pem";
    private static final String THING_CERT_FILE = "thingCert.crt";
    // FIXME: use DEFAULT_CONFIG_YAML_FILE_WRITE
    private static final String EFFECTIVE_CONFIG_FILE = "effectiveConfig.yaml";

    private static final String CONFIG_FOLDER = "config";

    // FIXME: join string constants from LogHelper
    private static final String ROOT_FOLDER = "/greengrass/v2";

    private static final ConcurrentHashMap<File, BaseProvisionManager> provisionManagerMap = new ConcurrentHashMap<>();

    private final Logger logger;
    private final String rootPath;
    private JsonNode config;

    /**
     * Gets BaseProvisionManager.
     *
     * @param filesDir path to files/ in application's directory
     */
    public static BaseProvisionManager getInstance(File filesDir) {
        return provisionManagerMap.computeIfAbsent(filesDir, c -> new BaseProvisionManager(filesDir));
    }

    /**
     * Creates BaseProvisionManager.
     *
     * @param filesDir path to files/ in application's directory
     */
    private BaseProvisionManager(File filesDir) {
        logger = LogHelper.getLogger(filesDir, getClass());
        rootPath = filesDir + ROOT_FOLDER;
    }

    /**
     * Checking is the Nucleus already provisioned.
     *
     * @return result true if Nucleus is already provisioned
     */
    @Override
    public boolean isProvisioned() {
        boolean provisioned = false;
        // Check effectiveConfig.yaml
        try (InputStream inputStream =
                     Files.newInputStream(Paths.get(String.format("%s/%s/%s", rootPath,
                             CONFIG_FOLDER, EFFECTIVE_CONFIG_FILE)))) {
            Yaml yaml = new Yaml();
            HashMap yamlMap = yaml.load(inputStream);
            // Access HashMaps and ArrayList by key(s)
            HashMap system = (HashMap) yamlMap.get(DeviceConfiguration.SYSTEM_NAMESPACE_KEY);
            String certPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH);
            String privKeyPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH);
            String rootCaPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH);

            if (!Utils.isEmpty(certPath)
                    && !Utils.isEmpty(privKeyPath)
                    && !Utils.isEmpty(rootCaPath)) {
                provisioned = true;
            }
        } catch (FileNotFoundException e) {
            logger.atError().setCause(e).log("Couldn't find {} file.", EFFECTIVE_CONFIG_FILE);
        } catch (Exception e) {
            logger.atError()
                    .setCause(e)
                    .log("An error occurred during parsing {}", EFFECTIVE_CONFIG_FILE);
        }
        return provisioned;
    }

    /**
     * Reset Nucleus provisioning and config files.
     */
    @Override
    public void clearProvision() {
        deleteRecursive(new File(String.format("%s/%s", rootPath, CONFIG_FOLDER)));
        new File(String.format("%s/%s", rootPath, PRIV_KEY_FILE)).delete();
        new File(String.format("%s/%s", rootPath, ROOT_CA_FILE)).delete();
        new File(String.format("%s/%s", rootPath, THING_CERT_FILE)).delete();
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
     * Get GreengrassSetup.main() arguments.
     *  In addition if required copy provisioning credentials to java system properties.
     *
     * @return array of strings with argument for Nucleus main()
     * @throws Exception on errors
     */
    @NonNull
    @Override
    public String[] prepareArguments() throws Exception {
        ArrayList<String> argumentList = new ArrayList<>();
        // If device isn't provisioned
        if (!isProvisioned() && config != null) {
            setupSystemProperties();
            argumentList = generateArguments();
            config = null;
            return argumentList.toArray(new String[argumentList.size()]);
        }
        // TODO: isn't required ?
        // final String[] nucleusArguments = {"--setup-system-service", "false"};
        return new String[0];
    }

    /**
     * Set provisioning info in JSON format.
     *
     * @param config provisioning config
     */
    @Override
    public void setConfig(@Nullable JsonNode config) {
        this.config = config;
    }

    @NonNull
    private ArrayList<String> generateArguments() {
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
     * Setup System properties.
     *
     * @throws Exception if the config is not valid
     */
    private void setupSystemProperties() throws Exception {
        if (!config.has(PROVISION_ACCESS_KEY_ID)) {
            logger.atError().log("Key {} is absent in the config file.", PROVISION_ACCESS_KEY_ID);
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_ACCESS_KEY_ID));
        } else {
            System.setProperty(PROVISION_ACCESS_KEY_ID, config.get(PROVISION_ACCESS_KEY_ID).asText());
        }
        if (!config.has(PROVISION_SECRET_ACCESS_KEY)) {
            logger.atError()
                    .log(String.format("Key {} is absent in the config file.", PROVISION_SECRET_ACCESS_KEY));
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_SECRET_ACCESS_KEY));
        } else {
            System.setProperty(PROVISION_SECRET_ACCESS_KEY, config.get(PROVISION_SECRET_ACCESS_KEY).asText());
        }
        if (config.has(PROVISION_SESSION_TOKEN)) {
            System.setProperty(PROVISION_SESSION_TOKEN, config.get(PROVISION_SESSION_TOKEN).asText());
        }
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
}
