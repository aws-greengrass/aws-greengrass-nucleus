/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Utils;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.GREENGRASS_SERVICE_ENDPOINT;
import static com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceClientFactory.CONTEXT_COMPONENT_SERVICE_ENDPOINT;
import static com.aws.iot.evergreen.packagemanager.PackageStore.CONTEXT_PACKAGE_STORE_DIRECTORY;
import static com.aws.iot.evergreen.util.Utils.HOME_PATH;

public class KernelCommandLine {
    public static final String MAIN_SERVICE_NAME = "main";

    private static final Logger logger = LogManager.getLogger(KernelCommandLine.class);
    private static final String HOME_DIR_PREFIX = "~/";
    private static final String ROOT_DIR_PREFIX = "~root/";
    private static final String CONFIG_DIR_PREFIX = "~config/";
    private static final String BIN_DIR_PREFIX = "~bin/";
    private static final String PACKAGE_DIR_PREFIX = "~packages/";

    private final Kernel kernel;

    @Getter
    private String providedConfigPathName;
    private String[] args;
    private String arg;
    private int argpos = 0;

    private static final String configPathName = "~root/config";
    private static final String clitoolPathName = "~root/bin";
    private static final String workPathName = "~root/work";
    private static final String packageStorePathName = "~root/packages";
    private static final String kernelAltsPathName = "~root/alts";

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
                case "--config":
                case "-i":
                    String configArg = getArg();
                    Objects.requireNonNull(configArg, "-i or --config requires an argument");
                    providedConfigPathName = deTilde(configArg);
                    break;
                case "--root":
                case "-r":
                    rootAbsolutePath = getArg();
                    Objects.requireNonNull(rootAbsolutePath, "-r or --root requires an argument");
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }
        if (Utils.isEmpty(rootAbsolutePath)) {
            rootAbsolutePath = "~/.evergreen";  // Default to hidden subdirectory of home.
        }
        rootAbsolutePath = deTilde(rootAbsolutePath);

        kernel.getConfig().lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths(Coerce.toString(topic)));

        // Endpoint for Beta CMS in us-east-1
        // TODO: Once service is available in multiple regions, this should not be a static config and
        // use the region value to determine endpoint
        kernel.getContext().put(CONTEXT_COMPONENT_SERVICE_ENDPOINT, GREENGRASS_SERVICE_ENDPOINT);
    }

    private void initPaths(String rootAbsolutePath) {
        // init all paths
        kernel.setRootPath(Paths.get(rootAbsolutePath).toAbsolutePath());
        Exec.setDefaultEnv("EVERGREEN_HOME", kernel.getRootPath().toString());
        kernel.setConfigPath(Paths.get(deTilde(configPathName)).toAbsolutePath());
        Exec.removePath(kernel.getClitoolPath());
        kernel.setClitoolPath(Paths.get(deTilde(clitoolPathName)).toAbsolutePath());
        Exec.addFirstPath(kernel.getClitoolPath());
        kernel.setWorkPath(Paths.get(deTilde(workPathName)).toAbsolutePath());
        Exec.setDefaultEnv("HOME", kernel.getWorkPath().toString());
        kernel.setPackageStorePath(Paths.get(deTilde(packageStorePathName)).toAbsolutePath());
        kernel.setKernelAltsPath(Paths.get(deTilde(kernelAltsPathName)).toAbsolutePath());
        try {
            Utils.createPaths(kernel.getRootPath(), kernel.getConfigPath(), kernel.getClitoolPath(),
                    kernel.getWorkPath(), kernel.getPackageStorePath());
        } catch (IOException e) {
            RuntimeException rte = new RuntimeException("Cannot create all required directories", e);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        // TODO: Add current kernel to local component store, if not exits.
        // Add symlinks for current Kernel alt, if not exits
        // Register Kernel Loader as system service (platform-specific), if not exits

        kernel.getContext().put(CONTEXT_PACKAGE_STORE_DIRECTORY, kernel.getPackageStorePath());
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
            if (!Exec.isWindows) {
                Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("r-xr-x---"));
            }
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
