/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.easysetup.GreengrassSetup;
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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Basic implementation of ProvisionManager interface.
 */
public final class BaseProvisionManager implements ProvisionManager {

    public static final String PROVISION_THING_NAME = "--thing-name";
    public static final String PROVISION_THING_NAME_SHORT = "-tn";
    public static final String THING_NAME_CHECKER = "[a-zA-Z0-9:_-]+";

    public static final String NUCLEUS_FOLDER = "aws.greengrass.Nucleus";

    public static final String PROVISION_ACCESS_KEY_ID = "aws.accessKeyId";
    public static final String PROVISION_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    public static final String PROVISION_SESSION_TOKEN = "aws.sessionToken";

    private static final String PRIVATE_KEY_FILE = "privKey.key";
    private static final String ROOT_CA_FILE = "rootCA.pem";
    private static final String THING_CERT_FILE = "thingCert.crt";
    private static final String CONFIG_YAML_FILE = "config.yaml";       // Kernel.DEFAULT_CONFIG_YAML_FILE_READ
    private static final String DISTRO_LINK = "distro";

    private static final ConcurrentHashMap<File, BaseProvisionManager> provisionManagerMap = new ConcurrentHashMap<>();

    private final Logger logger;
    private final WorkspaceManager workspaceManager;

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
        workspaceManager = WorkspaceManager.getInstance(filesDir);
    }


    /**
     * Checking is the Nucleus already provisioned.
     *
     * @return result true if Nucleus is already provisioned
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean isProvisioned() {
        boolean provisioned = false;
        // Check config.yaml
        try (InputStream inputStream
                     = Files.newInputStream(Paths.get(workspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE))) {
            Yaml yaml = new Yaml();
            HashMap yamlMap = yaml.load(inputStream);
            // Access HashMaps and ArrayList by key(s)
            HashMap system = (HashMap) yamlMap.get(DeviceConfiguration.SYSTEM_NAMESPACE_KEY);
            String certPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH);
            String privateKeyPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH);
            String rootCaPath = (String) system.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH);

            if (!Utils.isEmpty(certPath) && !Utils.isEmpty(privateKeyPath)  && !Utils.isEmpty(rootCaPath)) {
                provisioned = true;
            }
        } catch (FileNotFoundException e) {
            logger.atWarn().log("Couldn't find {} file.", CONFIG_YAML_FILE);
        } catch (NoSuchFileException e) {
            logger.atWarn().log("File {} doesn't exist.", CONFIG_YAML_FILE);
        } catch (Exception e) {
            logger.atWarn().log("File {} doesn't have provisioning configuration", CONFIG_YAML_FILE);
        }
        return provisioned;
    }

    /**
     * Execute automated provisioning.
     *
     * @param context context.
     * @param config new provisioning config.
     * @throws Exception on errors
     */
    @Override
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public void executeProvisioning(Context context, @NonNull JsonNode config) throws Exception {
        prepareAssetFiles(context);

        Kernel kernel = null;
        try {
            setupSystemProperties(config);
            final String[] provisioningArgs = prepareArgsForProvisioning(config);
            kernel = GreengrassSetup.mainForReturnKernel(provisioningArgs);
        } finally {
            clearSystemProperties();

            if (kernel != null) {
                writeConfig(kernel);
                kernel.shutdown();
            }
        }
    }

    /**
     * Reset Nucleus config files.
     */
    @Override
    public void clearNucleusConfig() {
        deleteFromDirectory(new File(workspaceManager.getConfigPath().toString()), CONFIG_YAML_FILE);
    }

    /**
     * Drop IoT thing credentials.
     *
     * @throws IOException on errors
     */
    @Override
    public void clearProvision() throws IOException {
        final String rootPath = workspaceManager.getRootPath().toString();
        final Path[] pathsToDelete = {
                Paths.get(workspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE),
                Paths.get(rootPath, PRIVATE_KEY_FILE),
                Paths.get(rootPath, ROOT_CA_FILE),
                Paths.get(rootPath, THING_CERT_FILE)
        };

        List<Exception> exceptions = new LinkedList<>();
        for (Path path: pathsToDelete) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                exceptions.add(e);
            }
        }
        if (!exceptions.isEmpty()) {
            // Aggregate the exceptions in order and throw a single failure exception
            StringBuilder failureMessage = new StringBuilder();
            exceptions.stream().map(Exception::toString).forEach(failureMessage::append);
            throw new IOException(failureMessage.toString());
        }
    }

    /**
     * Write system configuration file.
     *
     * @param kernel to set config for
     */
    private void writeConfig(Kernel kernel) {
        Path path = Paths.get(workspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE);
        try (CommitableWriter out = CommitableWriter.abandonOnClose(path)) {
            kernel.writeSystemConfig(out);
            out.commit();
            logger.atInfo().setEventType("config-dump-complete").addKeyValue("file", path).log();
        } catch (IOException t) {
            logger.atInfo().setEventType("config-dump-error").setCause(t).addKeyValue("file", path).log();
        }
    }

    /**
     * Get GreengrassSetup.main() arguments.
     *  In addition if required copy provisioning credentials to java system properties.
     *
     * @param config new provisioning config
     *
     * @return array of strings with argument for Nucleus main()
     * @throws Exception on errors
     */
    @NonNull
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private String[] prepareArgsForProvisioning(@NonNull JsonNode config) throws Exception {
        return generateArguments(config);
    }

    /**
     * Get thing name.
     *
     * @return Thing name.
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public String getThingName() {
        String thingName = null;
        try (InputStream inputStream = Files.newInputStream(
                Paths.get(workspaceManager.getConfigPath().toString(), CONFIG_YAML_FILE))) {
            Yaml yaml = new Yaml();
            HashMap yamlMap = yaml.load(inputStream);
            // Access HashMaps and ArrayList by key(s)
            HashMap system = (HashMap) yamlMap.get(DeviceConfiguration.SYSTEM_NAMESPACE_KEY);
            thingName = (String) system.get(DeviceConfiguration.DEVICE_PARAM_THING_NAME);
        } catch (FileNotFoundException e) {
            logger.atWarn().log("Couldn't find {} file.", CONFIG_YAML_FILE);
        } catch (NoSuchFileException e) {
            logger.atWarn().log("File {} doesn't exist.", CONFIG_YAML_FILE);
        } catch (Exception e) {
            logger.atWarn().log("File {} doesn't have thing name.",
                    CONFIG_YAML_FILE);
        }
        return thingName;
    }

    @NonNull
    private String[] generateArguments(@NonNull JsonNode config) {
        List<String> argsList = new ArrayList<>();
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

        return argsList.toArray(new String[0]);
    }

    /**
     * Remove all files from directory except one exception.
     *
     * @param directory directory with configuration files
     * @param except do not remove that file
     */
    private void deleteFromDirectory(@NonNull File directory, @NonNull String except) {
        if (directory.isDirectory()) {
            File[] list = directory.listFiles();
            if (list != null) {
                deleteFiles(list, except);
            }
        }
    }

    private void deleteFiles(File[] list, String except) {
        for (File child: list) {
            if (!except.equals(child.getName())) {
                boolean isDeleted = child.delete();
                if (!isDeleted) {
                    throw new RuntimeException("Couldn't delete file " + child.getName());
                }
            }
        }
    }

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
            rootUnpackDir = new File(workspaceManager.getUnarchivedPath()
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
     * Get Nucleus version in Assets.
     * @// TODO: 28.04.2022   remove that logic due to file coping already implemented by
     *      Nucleus base provision logic, we need just change source of recipe.yaml and so one files
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
     * Copy asset files to the artifacts-unarchived/path folder.
     * @// TODO: 28.04.2022   remove that logic due to file coping already implemented by
     *      Nucleus base provision logic, we need just change source of recipe.yaml and so one files
     *
     * @param context context.
     * @param path path in packages/artifacts-unarchived
     *
     * @return result of copy.
     */
    @SuppressWarnings("PMD.AvoidFileStream")
    private boolean copyAssetFiles(@NonNull Context context, Path path) {
        try {
            String [] list = context.getAssets().list(path.toString());
            // This is a folder
            for (String file : list) {
                if (copyAssetFiles(context, path.resolve(file))) {
                    try {
                        Path targetPath = workspaceManager.getUnarchivedPath().resolve(path);
                        Utils.createPaths(targetPath);

                        File targetFile = new File(targetPath.resolve(file).toString());
                        targetFile.createNewFile();

                        try (InputStream in = context.getAssets().open(path.resolve(file).toString());
                             OutputStream out = new FileOutputStream(targetFile)) {
                            copy(in, out);
                        } catch (IOException e) {
                            // It is just a folder. Do nothing
                            logger.atWarn().log("{} is not a file.", file);
                        }
                    } catch (IOException e) {
                        logger.atError().setCause(e).log("Failed to copy asset file {}.",
                                path.resolve(file).toString());
                    }
                } else {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    /**
     * Setup System properties.
     *
     * @param config new provisioning config
     *
     * @throws Exception if the config is not valid
     */
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private void setupSystemProperties(@NonNull JsonNode config) throws Exception {
        if (!config.has(PROVISION_ACCESS_KEY_ID)) {
            logger.atError().log("Key {} is absent in the config file.", PROVISION_ACCESS_KEY_ID);
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_ACCESS_KEY_ID));
        }

        if (!config.has(PROVISION_SECRET_ACCESS_KEY)) {
            logger.atError().log("Key {} is absent in the config file.", PROVISION_SECRET_ACCESS_KEY);
            throw new Exception(String.format("Parameters do not contain \"%s\" key", PROVISION_SECRET_ACCESS_KEY));
        }

        System.setProperty(PROVISION_ACCESS_KEY_ID, config.get(PROVISION_ACCESS_KEY_ID).asText());
        System.setProperty(PROVISION_SECRET_ACCESS_KEY, config.get(PROVISION_SECRET_ACCESS_KEY).asText());
        if (config.has(PROVISION_SESSION_TOKEN)) {
            System.setProperty(PROVISION_SESSION_TOKEN, config.get(PROVISION_SESSION_TOKEN).asText());
        }
    }

    /**
     * Clear SystemProperties.
     */
    private void clearSystemProperties() {
        System.clearProperty(PROVISION_ACCESS_KEY_ID);
        System.clearProperty(PROVISION_SECRET_ACCESS_KEY);
        System.clearProperty(PROVISION_SESSION_TOKEN);
    }

    /**
     * Prepare asset files.
     * @// TODO: 28.04.2022   remove that logic due to file coping already implemented by
     *      Nucleus base provision logic, we need just change source of recipe.yaml and so one files
     * @param context context.
     */
    private void prepareAssetFiles(Context context) {
        final Path current = workspaceManager.getCurrentPath();
        final Path currentDistroLink = current.resolve(DISTRO_LINK);

        if (isFolderEmpty(currentDistroLink)) {
            final Path init = workspaceManager.getInitPath();
            final Path initDistroLink = init.resolve(DISTRO_LINK);

            copyAssetFiles(context, Paths.get(NUCLEUS_FOLDER));
            String version = getNucleusVersionFromAssets(context, Paths.get(NUCLEUS_FOLDER));

            try {
                Utils.createPaths(init);
                Files.deleteIfExists(initDistroLink);
                Files.deleteIfExists(current);
            } catch (IOException e) {
                logger.atError().setCause(e).log("Couldn't create folders or remove links.");
            }

            if (version != null) {
                try {
                    Files.createSymbolicLink(initDistroLink, getCurrentUnpackDir(version));
                    Files.createSymbolicLink(current, init);
                } catch (IOException e) {
                    logger.atError().setCause(e).log("Unable to create symbolic links.");
                }
            }
        }
    }
}
