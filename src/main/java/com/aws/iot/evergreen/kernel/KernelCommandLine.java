/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.util.Utils.HOME_PATH;

public class KernelCommandLine {
    private static final Logger logger = LogManager.getLogger(KernelCommandLine.class);
    private static final String HOME_DIR_PREFIX = "~/";
    private static final String ROOT_DIR_PREFIX = "~root/";
    private static final String CONFIG_DIR_PREFIX = "~config/";
    private static final String BIN_DIR_PREFIX = "~bin/";
    private static final String PACKAGE_DIR_PREFIX = "~packages/";

    private final Kernel kernel;
    boolean haveRead = false;
    String mainServiceName = "main";
    private String[] args;
    private String arg;
    private int argpos = 0;

    private static final String configPathName = "~root/config";
    private static final String clitoolPathName = "~root/bin";
    private static final String workPathName = "~root/work";
    private static final String packageStorePathName = "~root/packages";

    public static void main(String[] args) {
        new Kernel().parseArgs(args).launch();
    }

    public KernelCommandLine(Kernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Parse command line arguments before starting.
     *
     * @param args user-provided arguments
     */
    public void parseArgs(String... args) {
        this.args = args;

        // Get root path from System Property/JVM argument. Default handled after 'while'
        String rootAbsolutePath = System.getProperty("root");

        while (getArg() != null) {
            switch (arg.toLowerCase()) {
                case "-config":
                case "-i":
                    try {
                        String configArg = getArg();
                        Objects.requireNonNull(configArg, "-i or -config requires an argument");
                        kernel.getConfig().read(deTilde(configArg));
                        haveRead = true;
                    } catch (IOException ex) {
                        // Usually we don't want to log and throw at the same time because it can produce duplicate logs
                        // if the handler of the exception also logs. However since we use structured logging, I
                        // decide to log the error so that the future logging parser can parse the exceptions.
                        RuntimeException rte =
                                new RuntimeException(String.format("Can't read the config file %s", arg), ex);
                        logger.atError().setEventType("parse-args-error").setCause(rte).log();
                        throw rte;
                    }
                    break;
                case "-root":
                case "-r":
                    rootAbsolutePath = getArg();
                    Objects.requireNonNull(rootAbsolutePath, "-r or -root requires an argument");
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }
        // If no config path was given then initialize with default blank config
        if (!haveRead) {
            kernel.getConfig()
                    .lookup(SERVICES_NAMESPACE_TOPIC, mainServiceName, SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run")
                    .withValue("echo \"Main Running\"");
        }
        if (Utils.isEmpty(rootAbsolutePath)) {
            rootAbsolutePath = "~/.evergreen";  // Default to hidden subdirectory of home.
        }
        rootAbsolutePath = deTilde(rootAbsolutePath);

        kernel.getConfig().lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths(Coerce.toString(topic)));

        // Always initialize default credential provider, can be overridden before launch if needed
        // TODO: This should be replaced by a Token Exchange Service credential provider
        AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
        kernel.getContext().put("greengrassServiceCredentialProvider", credentialsProvider);
    }

    private void initPaths(String rootAbsolutePath) {
        // init all paths
        kernel.setRootPath(Paths.get(rootAbsolutePath));
        Exec.setDefaultEnv("EVERGREEN_HOME", kernel.getRootPath().toString());
        kernel.setConfigPath(Paths.get(deTilde(configPathName)));
        Exec.removePath(kernel.getClitoolPath());
        kernel.setClitoolPath(Paths.get(deTilde(clitoolPathName)));
        Exec.addFirstPath(kernel.getClitoolPath());
        kernel.setWorkPath(Paths.get(deTilde(workPathName)));
        Exec.setDefaultEnv("HOME", kernel.getWorkPath().toString());
        kernel.setPackageStorePath(Paths.get(deTilde(packageStorePathName)));
        try {
            Utils.createPaths(kernel.getRootPath(), kernel.getConfigPath(), kernel.getClitoolPath(),
                    kernel.getWorkPath(), kernel.getPackageStorePath());
        } catch (IOException e) {
            RuntimeException rte =
                    new RuntimeException("Cannot create all required directories", e);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        kernel.getContext().put("packageStoreDirectory", kernel.getPackageStorePath());
    }

    /**
     * Install the CLI tool from the URL into the home directory.
     *
     * @param resource URL of the file to install
     */
    public void installCliTool(URL resource) {
        try {
            String dp = resource.getPath();
            int sl = dp.lastIndexOf('/');
            if (sl >= 0) {
                dp = dp.substring(sl + 1);
            }
            Path dest = kernel.getClitoolPath().resolve(dp);
            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("r-xr-x---"));
        } catch (IOException t) {
            logger.atError().setEventType("cli-install-error").setCause(t).log();
        }
    }

    /**
     * Take a user-provided string which represents a path and resolve it to an absolute path.
     *
     * @param s String to resolve
     * @return resolved path
     */
    public String deTilde(String s) {
        if (s.startsWith(HOME_DIR_PREFIX)) {
            s = HOME_PATH.resolve(s.substring(HOME_DIR_PREFIX.length())).toString();
        }
        if (kernel.getRootPath() != null && s.startsWith(ROOT_DIR_PREFIX)) {
            s = kernel.getRootPath().resolve(s.substring(ROOT_DIR_PREFIX.length())).toString();
        }
        if (kernel.getConfigPath() != null && s.startsWith(CONFIG_DIR_PREFIX)) {
            s = kernel.getConfigPath().resolve(s.substring(CONFIG_DIR_PREFIX.length())).toString();
        }
        if (kernel.getClitoolPath() != null && s.startsWith(BIN_DIR_PREFIX)) {
            s = kernel.getClitoolPath().resolve(s.substring(BIN_DIR_PREFIX.length())).toString();
        }
        if (kernel.getPackageStorePath() != null && s.startsWith(PACKAGE_DIR_PREFIX)) {
            s = kernel.getPackageStorePath().resolve(s.substring(PACKAGE_DIR_PREFIX.length())).toString();
        }
        return s;
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = args == null || argpos >= args.length ? null : args[argpos++];
    }
}
