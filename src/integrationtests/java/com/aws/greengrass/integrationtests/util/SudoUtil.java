package com.aws.greengrass.integrationtests.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;

import java.io.IOException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class SudoUtil {
    private static final String LOGBASH = "/usr/bin/logbash";

    private SudoUtil() {

    }

    /**
     * Skip test if current user cannot sudo to default kernel shell. Logbash should be enabled for systems where users
     * cannot sudo to shell, but can sudo to logbash.
     *
     * @param kernel        a kernel to check.
     * @param enableLogbash set to true if a fallback to {@value #LOGBASH} should be enabled.
     */
    public static void assumeCanSudoShell(Kernel kernel, boolean enableLogbash) {
        assumeTrue(canSudoShell(kernel, enableLogbash), "cannot sudo to shell as current user");
    }

    /**
     * Check if current user can sudo to default kernel shell. Logbash should be enabled for systems where users cannot
     * sudo to shell, but can sudo to logbash.
     *
     * @param kernel        a kernel to check.
     * @param enableLogbash set to true if a fallback to {@value #LOGBASH} should be enabled.
     */
    public static boolean canSudoShell(Kernel kernel, boolean enableLogbash) {
        DeviceConfiguration config = new DeviceConfiguration(kernel);
        String shell = Coerce.toString(config.getRunWithDefaultPosixShell().getOnce());

        if (canSudoShell(shell)) {
            return true;
        }
        if (enableLogbash && canSudoShell(LOGBASH)) {
            config.getRunWithDefaultPosixShell().withValue(LOGBASH);
            return true;
        }
        return false;
    }

    /**
     * Check if current user can sudo to specified shell.
     *
     * @param shell a shell to check
     * @return true if the user can sudo to the shell.
     */
    public static boolean canSudoShell(String shell) {
        try {
            return Exec.successful(false, "sudo -u \\#123456 " + shell + " -c ls");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
