package com.aws.greengrass.android.provision;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.google.gson.Gson;

import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class ProvisionManager {

    public static boolean isConfigValidWithoutThingName(@NonNull ProvisionConfig config) {
        return !TextUtils.isEmpty(config.awsAccessKeyId)
                && !TextUtils.isEmpty(config.awsSecretAccessKey)
                && !TextUtils.isEmpty(config.awsRegion)
                && !TextUtils.isEmpty(config.thingGroupName)
                && !TextUtils.isEmpty(config.thingPolicyName)
                && !TextUtils.isEmpty(config.tesRoleAliasName)
                && !TextUtils.isEmpty(config.componentDefaultUser)
                && !TextUtils.isEmpty(config.provision)
                && !TextUtils.isEmpty(config.setupSystemService);
    }

    public static boolean isProvisioned() {
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
            //FIXME: do we need logger there?
            //logger.atError().setCause(e).log("Couldn't find effectiveConfig.yaml file.");
        } catch (Exception e) {
            //FIXME: do we need logger there?
            //logger.atError().setCause(e).log("Some Other Exception");
        }
        return provisioned;
    }

    @Nullable
    public static ProvisionConfig parseFile(@NonNull Context context,
                                            @NonNull Uri sourceUri) {
        BufferedReader reader = null;
        InputStream input;
        ProvisionConfig config = null;

        try {
            input = context.getContentResolver().openInputStream(sourceUri);
            reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            config = new Gson().fromJson(reader, ProvisionConfig.class);
        } catch (Throwable e) {
            e.printStackTrace();
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
