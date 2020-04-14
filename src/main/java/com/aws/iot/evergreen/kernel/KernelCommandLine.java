/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Utils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import static com.aws.iot.evergreen.util.Utils.HOME_PATH;

public class KernelCommandLine {
    private static final Logger logger = LogManager.getLogger(KernelCommandLine.class);
    private static final String done = " missing "; // unique marker
    private final Kernel kernel;
    boolean forReal = true;
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
    public Kernel parseArgs(String... args) {
        this.args = args;

        // Get root path from System Property/JVM argument. Default handled after 'while'
        String rootAbsolutePath = System.getProperty("root");

        while (!Objects.equals(getArg(), done)) {
            switch (arg.toLowerCase()) {
                case "-dryrun":
                    forReal = false;
                    break;
                case "-forreal":
                    forReal = true;
                    break;
                case "-config":
                case "-i":
                    try {
                        kernel.config.read(deTilde(getArg()));
                        haveRead = true;
                    } catch (IOException ex) {
                        // Usually we don't want to log and throw at the same time because it can produce duplicate logs
                        // if the handler of the exception also logs. However since we use structured logging, I
                        // decide to log the error so that the future logging parser can parse the exceptions.
                        RuntimeException rte =
                                new RuntimeException(String.format("Can't read the config file %s", getArg()), ex);
                        logger.atError().setEventType("parse-args-error").setCause(rte).log();
                        throw rte;
                    }
                    break;
                case "-root":
                case "-r":
                    rootAbsolutePath = getArg();
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

        kernel.config.lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths(Coerce.toString(topic)));
        kernel.context.get(EZTemplates.class).addEvaluator(expr -> {
            Object value;
            switch (expr) {
                case "root":
                    value = kernel.rootPath;
                    break;
                case "work":
                    value = kernel.workPath;
                    break;
                case "bin":
                    value = kernel.clitoolPath;
                    break;
                case "config":
                    value = kernel.configPath;
                    break;
                default:
                    value = System.getProperty(expr, System.getenv(expr));
                    if (value == null) {
                        value = kernel.config.find(Configuration.splitPath(expr));
                    }
                    break;
            }
            return value;
        });
        return kernel;
    }

    private void initPaths(String rootAbsolutePath) {
        // init all paths
        kernel.rootPath = Paths.get(rootAbsolutePath);
        kernel.configPath = Paths.get(deTilde(configPathName));
        Exec.removePath(kernel.clitoolPath);
        kernel.clitoolPath = Paths.get(deTilde(clitoolPathName));
        Exec.addFirstPath(kernel.clitoolPath);
        kernel.workPath = Paths.get(deTilde(workPathName));
        Exec.setDefaultEnv("HOME", kernel.workPath.toString());
        kernel.packageStorePath = Paths.get(deTilde(packageStorePathName));
        try {
            Utils.createPaths(kernel.rootPath, kernel.configPath, kernel.clitoolPath, kernel.workPath,
                    kernel.packageStorePath);
        } catch (IOException e) {
            RuntimeException rte =
                    new RuntimeException("Cannot create all required directories", e);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        kernel.context.put("packageStoreDirectory", kernel.packageStorePath);
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
            Path dest = kernel.clitoolPath.resolve(dp);
            kernel.context.get(EZTemplates.class).rewrite(resource, dest);
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
        if (s.startsWith("~/")) {
            s = HOME_PATH.resolve(s.substring(2)).toString();
        }
        if (kernel.rootPath != null && s.startsWith("~root/")) {
            s = kernel.rootPath.resolve(s.substring(6)).toString();
        }
        if (kernel.configPath != null && s.startsWith("~config/")) {
            s = kernel.configPath.resolve(s.substring(8)).toString();
        }
        if (kernel.clitoolPath != null && s.startsWith("~bin/")) {
            s = kernel.clitoolPath.resolve(s.substring(5)).toString();
        }
        return s;
    }

    private String getArg() {
        return arg = args == null || argpos >= args.length ? done : args[argpos++];
    }
}
