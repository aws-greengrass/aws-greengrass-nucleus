/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Basic implementation of ProvisionManager interface.
 */
public class BaseProvisionManager implements ProvisionManager {

    public static final String PROVISION_THING_NAME = "--thing-name";
    public static final String PROVISION_THING_NAME_SHORT = "-tn";
    public static final String THING_NAME_CHECKER = "[a-zA-Z0-9:_-]+";

    public static final String NUCLEUS_FOLDER = "aws.greengrass.Nucleus";

    public static final String PROVISION_ACCESS_KEY_ID = "aws.accessKeyId";
    public static final String PROVISION_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    public static final String PROVISION_SESSION_TOKEN = "aws.sessionToken";
    private static final String PRIV_KEY_FILE = "privKey.key";
    private static final String ROOT_CA_FILE = "rootCA.pem";
    private static final String THING_CERT_FILE = "thingCert.crt";
    private static final String CONFIG_YAML_FILE = "config.yaml";
    private static final String CURRENT_DISTRO_LINK = "distro";

    private static final ConcurrentHashMap<File, BaseProvisionManager> provisionManagerMap = new ConcurrentHashMap<>();

    private final Logger logger;
    private final String rootPath;
    private JsonNode config;

    /**
     * Find a folder with specific name.
     *
     * @param root search folder.
     * @param name needed folder name.
     *
     * @return absolute path to the needed folder.
     */
    @Nullable
    private static Path findDir(@NonNull File root, @NonNull String name) {
        if (root.getName().equals(name)) {
            return Paths.get(root.getAbsolutePath());
        }
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    return findDir(f, name);
                }
            }
        }
        return null;
    }

    /**
     * Check if a folder is empty.
     *
     * @param dirPath path to the folder.
     *
     * @return true if the folder is empty or doesn't exist.
     */
    private boolean isFolderEmpty(Path dirPath) {
        boolean isEmpty = true;
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
            isEmpty = !dirStream.iterator().hasNext();
        } catch (IOException e) {
            logger.atWarn().log("Folder {} doesn't exist yet.", dirPath);
        }
        return isEmpty;
    }

    /**
     * Get current unpack directory.
     *
     * @param version Nucleus version.
     *
     * @return path to the current unpack directory.
     */
    @Nullable
    private Path getCurrentUnpackDir(String version) {
        File rootUnpackDir = null;

        try {
            rootUnpackDir = new File(WorkspaceManager.getUnarchivedPath()
                    .resolve(DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME)
                    .resolve(version)
                    .toString()).getCanonicalFile();
        } catch (IOException e) {
            logger.atError().setCause(e).log("Couldn't create a new file.");
        }
        String name = DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME.toLowerCase();


        if (rootUnpackDir != null) {
            return findDir(rootUnpackDir, name);
        }
        return null;
    }

    /**
     * Prepare asset files.
     *
     * @param context context.
     */
    public void prepareAssetFiles(Context context) {
        Path distro = WorkspaceManager.getCurrentDistroPath();
        Path distroLink = distro.resolve(CURRENT_DISTRO_LINK);

        if (isFolderEmpty(distroLink)) {
            copyAssetFiles(context, Paths.get(NUCLEUS_FOLDER));
            String version = getNucleusVersionFromAssets(context, Paths.get(NUCLEUS_FOLDER));

            try {
                Utils.createPaths(distro);
                Files.deleteIfExists(distroLink);
            } catch (IOException e) {
                logger.atError().setCause(e).log("Couldn't create folders.");
            }

            try {
                Files.createSymbolicLink(distroLink, getCurrentUnpackDir(version));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Unable to create symbolic link.");
            }
        }
    }

    /**
     * Get Nucleus version in Assets.
     *
     * @param context context.
     * @param path root assets path.
     *
     * @return Nucleus version.
     */
    private String getNucleusVersionFromAssets(@NonNull Context context, Path path) {
        try {
            String [] list = context.getAssets().list(path.toString());
            // Get a folder name - is the Nucleus version
            for (String file : list) {
                if (file != null) {
                    return file;
                }
            }
        } catch (IOException e) {
            return null;
        }

        return null;
    }

    /**
     * Copy asset files to the ROOT_INIT_FOLDER folder.
     *
     * @param context context.
     * @param path root assets path.
     *
     * @return result of copy.
     */
    private boolean copyAssetFiles(@NonNull Context context, Path path) {
        try {
            String [] list = context.getAssets().list(path.toString());
            // This is a folder
            for (String file : list) {
                if (!copyAssetFiles(context, path.resolve(file))) {
                    return false;
                } else {
                    try {
                        Path targetPath = WorkspaceManager.getUnarchivedPath().resolve(path);
                        Utils.createPaths(targetPath);

                        File targetFile = new File(targetPath.resolve(file).toString());
                        targetFile.createNewFile();

                        try (InputStream in = context.getAssets().open(path.resolve(file).toString());
                             OutputStream out = new FileOutputStream(targetFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        } catch (IOException e) {
                            // It is just a folder. Do nothing
                            logger.atWarn().log("{} is not a file.", file);
                        }
                    } catch (IOException e) {
                        logger.atError().setCause(e).log("Failed to copy asset file {}.",
                                path.resolve(file).toString());
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }

        return true;
    }

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
        rootPath = WorkspaceManager.getInstance(filesDir).getRootPath().toString();
    }

    /**
     * Checking is the Nucleus already provisioned.
     *
     * @return result true if Nucleus is already provisioned
     */
    @Override
    public boolean isProvisioned() {
        boolean provisioned = false;
        // Check config.yaml
        try (InputStream inputStream = Files.newInputStream(
                Paths.get(WorkspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE))) {
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
            logger.atWarn().log("Couldn't find {} file.", CONFIG_YAML_FILE);
        } catch (NoSuchFileException e) {
            logger.atWarn().log("File {} doesn't exist.", CONFIG_YAML_FILE);
        } catch (Exception e) {
            logger.atWarn().log("File {} doesn't have provisioning configuration",
                    CONFIG_YAML_FILE);
        }
        return provisioned;
    }

    /**
     * Reset Nucleus config files.
     */
    @Override
    public void clearNucleusConfig() {
        deleteConfigRecursive(new File(WorkspaceManager.getConfigPath().toString()));
    }

    /**
     * Reset Nucleus provisioning.
     */
    @Override
    public void clearProvision() {
        new File(String.format("%s/%s", WorkspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE)).delete();
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
     * Write system configuration file.
     *
     * @param kernel to set config for
     */
    @Override
    public void writeConfig(Kernel kernel) {
        Path p = Paths.get(String.format("%s/%s",
                WorkspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE));
        try (CommitableWriter out = CommitableWriter.abandonOnClose(p)) {
            kernel.writeSystemConfig(out);
            out.commit();
            logger.atInfo().setEventType("config-dump-complete").addKeyValue("file", p).log();
        } catch (IOException t) {
            logger.atInfo().setEventType("config-dump-error").setCause(t).addKeyValue("file", p).log();
        }
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
        argsList.add("--provision");
        argsList.add("true");

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

    /**
     * Remove all configuration files except system configuration.
     *
     * @param fileOrDirectory directory with configuration files
     */
    private void deleteConfigRecursive(@NonNull File fileOrDirectory) {
        File[] list;
        if (fileOrDirectory.isDirectory()) {
            list = fileOrDirectory.listFiles();
            if (list != null) {
                for (File child : list) {
                    if (child.getName().equals(CONFIG_YAML_FILE)) {
                        continue;
                    }
                    child.delete();
                }
            }
        }
    }
}
