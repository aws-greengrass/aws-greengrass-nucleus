/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.factoryreset;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.tes.TokenExchangeService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.greengrassv2.model.CancelDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeleteCoreDeviceRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeleteDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentStatus;
import software.amazon.awssdk.services.greengrassv2.model.ListDeploymentsRequest;
import software.amazon.awssdk.services.greengrassv2.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.lifecyclemanager.Kernel.FACTORY_RESET_TLOG_FILE;

/**
 * Core logic for factory reset — Phase 2 (IPC-triggered).
 *
 * <p>Sequence:
 * <ol>
 *   <li>(Optional, best-effort) Call greengrassv2:DeleteCoreDevice via TES credentials to clean cloud state.</li>
 *   <li>Close the transaction log writer.</li>
 *   <li>Restore config/config.tlog from config/factory-reset.tlog.</li>
 *   <li>Wipe deployments/, work/, plugins/untrusted/.</li>
 *   <li>Wipe telemetry/, logs/.</li>
 *   <li>Remove stale IPC files (ipc.socket, cli_ipc_info/).</li>
 *   <li>Restart the nucleus (exit with REQUEST_RESTART code).</li>
 * </ol>
 */
public class FactoryResetAgent {

    private static final Logger logger = LogManager.getLogger(FactoryResetAgent.class);

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private Kernel kernel;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private DeviceConfiguration deviceConfiguration;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private TokenExchangeService tokenExchangeService;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private NucleusPaths nucleusPaths;

    /**
     * Perform factory reset.
     *
     * <p>This method MUST be called from a background thread (not the IPC handler thread) so that
     * the IPC response has already been sent before the nucleus shuts down.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Best-effort: call greengrassv2:DeleteCoreDevice via TES credentials.</li>
     *   <li>Restore config/config.tlog from config/factory-reset.tlog.</li>
     *   <li>Wipe deployments/, work/, plugins/untrusted/.</li>
     *   <li>Wipe telemetry/, empty logs/.</li>
     *   <li>Remove stale IPC files (cli_ipc_info/).</li>
     *   <li>Call kernel.shutdown(30, REQUEST_RESTART) → System.exit(100) → loader restarts.</li>
     * </ol>
     *
     * <p>NOTE: Do NOT call softShutdown() here — it stops all services (including IPC) before
     * the response is sent, calls writeEffectiveConfig() which overwrites the just-restored config,
     * and can prevent System.exit from being reached. Instead, kernel.shutdown(30, REQUEST_RESTART)
     * performs a full orderly shutdown followed by System.exit(100) which the loader catches to restart.
     */
    public void performFactoryReset() {
        // Step 1: best-effort cloud cleanup
        tryCleanupCloudDeployments();
        tryDeleteCoreDevice();

        Path configPath = nucleusPaths.configPath();
        Path snapshotPath = configPath.resolve(FACTORY_RESET_TLOG_FILE);

        if (!Files.exists(snapshotPath)) {
            throw new IllegalStateException(
                    "Factory reset snapshot not found at " + snapshotPath
                            + ". The device may not have been fully provisioned yet.");
        }

        // Step 2: Restore config.tlog from factory-reset.tlog
        try {
            Path configTlog = configPath.resolve("config.tlog");
            Path configTlogBackup = configPath.resolve("config.tlog~");
            Path effectiveConfigYaml = configPath.resolve("effectiveConfig.yaml");
            Files.copy(snapshotPath, configTlog, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(configTlogBackup);
            Files.deleteIfExists(effectiveConfigYaml);
            logger.atInfo().log("Restored config.tlog from factory-reset.tlog snapshot");
        } catch (IOException e) {
            throw new RuntimeException("Failed to restore config from factory-reset.tlog", e);
        }

        // Step 3: Wipe deployed content
        deleteRecursively(nucleusPaths.deploymentPath());
        deleteRecursively(nucleusPaths.workPath());
        deleteRecursively(nucleusPaths.pluginPath().resolve("untrusted"));

        // Step 4: Wipe accumulated runtime state
        deleteRecursively(nucleusPaths.rootPath().resolve("telemetry"));
        // Empty the logs/ directory but keep it — the systemd service redirects loader
        // output to logs/loader.log before the JVM starts, so the directory must exist.
        emptyDirectory(nucleusPaths.loaderLogsPath().getParent());

        // Step 5: Clean IPC runtime files
        deleteRecursively(nucleusPaths.cliIpcInfoPath());

        logger.atInfo().log("Factory reset local cleanup complete. Restarting nucleus.");

        // Step 6: Orderly shutdown with exit code 100 so the loader re-execs.
        //
        // kernel.shutdown() → softShutdown() → writeEffectiveConfig() will overwrite config.tlog
        // with the current in-memory config (which still has the old deployed-component entries).
        // To prevent this, we register a JVM shutdown hook that re-copies the snapshot to config.tlog
        // AFTER writeEffectiveConfig runs but BEFORE the JVM fully exits. Shutdown hooks run
        // after System.exit() is called, giving us a final chance to fix the file.
        final Path snapshotFinal = snapshotPath;
        final Path configTlogFinal = configPath.resolve("config.tlog");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.copy(snapshotFinal, configTlogFinal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // best-effort — if this fails the device will need a manual fix
            }
        }, "factory-reset-config-restore-hook"));

        kernel.shutdown(30, REQUEST_RESTART);
    }

    /**
     * Best-effort: list, cancel, and delete all deployments targeting this thing via TES credentials.
     * This cleans up the deployment view in the Greengrass console.
     */
    private void tryCleanupCloudDeployments() {
        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        try (GreengrassV2Client ggClient = GreengrassV2Client.builder()
                .credentialsProvider(tokenExchangeService)
                .region(Region.of(region))
                .build();
             StsClient stsClient = StsClient.builder()
                .credentialsProvider(tokenExchangeService)
                .region(Region.of(region))
                .build()) {

            String accountId = stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
            String thingArn = String.format("arn:aws:iot:%s:%s:thing/%s", region, accountId, thingName);

            ListDeploymentsResponse listResponse = ggClient.listDeployments(
                    ListDeploymentsRequest.builder()
                            .targetArn(thingArn)
                            .build());

            for (software.amazon.awssdk.services.greengrassv2.model.Deployment deployment
                    : listResponse.deployments()) {
                String deploymentId = deployment.deploymentId();
                try {
                    // Cancel active deployments first
                    if (deployment.deploymentStatus() == DeploymentStatus.ACTIVE) {
                        ggClient.cancelDeployment(
                                CancelDeploymentRequest.builder()
                                        .deploymentId(deploymentId)
                                        .build());
                        logger.atInfo().kv("deploymentId", deploymentId).log("Cancelled cloud deployment");
                    }
                    // Delete the deployment entry from console
                    ggClient.deleteDeployment(
                            DeleteDeploymentRequest.builder()
                                    .deploymentId(deploymentId)
                                    .build());
                    logger.atInfo().kv("deploymentId", deploymentId).log("Deleted cloud deployment");
                } catch (Exception e) {
                    logger.atWarn().setCause(e).kv("deploymentId", deploymentId)
                            .log("Failed to clean up cloud deployment, skipping");
                }
            }
            logger.atInfo().kv("thingName", thingName).log("Cloud deployment cleanup complete");
        } catch (Exception e) {
            logger.atWarn().setCause(e).log(
                    "Cloud deployment cleanup failed. Proceeding with local-only factory reset.");
        }
    }

    /**
     * Attempt to call greengrassv2:DeleteCoreDevice using TES credentials.
     * Best-effort — AccessDeniedException is logged as a warning and does not block local reset.
     */
    private void tryDeleteCoreDevice() {
        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        try (GreengrassV2Client client = GreengrassV2Client.builder()
                .credentialsProvider(tokenExchangeService)   // TES provides temporary IAM credentials
                .region(Region.of(region))
                .build()) {
            client.deleteCoreDevice(
                    DeleteCoreDeviceRequest.builder()
                            .coreDeviceThingName(thingName)
                            .build());
            logger.atInfo().kv("thingName", thingName).log("Successfully deleted core device from cloud");
        } catch (software.amazon.awssdk.services.greengrassv2.model.AccessDeniedException e) {
            logger.atWarn().log(
                    "Cloud cleanup skipped: TES role lacks greengrass:DeleteCoreDevice permission. "
                    + "Add this permission to your TES IAM role to enable cloud cleanup. "
                    + "Proceeding with local-only factory reset.");
        } catch (Exception e) {
            logger.atWarn().setCause(e).log(
                    "Cloud cleanup failed (deleteCoreDevice). Proceeding with local-only factory reset.");
        }
    }

    /**
     * Delete all files and subdirectories inside {@code dir} but keep the directory itself.
     * Errors are logged but do not abort the reset.
     *
     * @param dir the directory to empty
     */
    private void emptyDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .filter(p -> !p.equals(dir)) // keep the root directory itself
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.atWarn().setCause(e).log("Failed to delete {}", p);
                    }
                });
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Failed to walk directory for emptying: {}", dir);
        }
    }

    /**
     * Recursively delete a directory tree. Errors are logged but do not abort the reset.
     *
     * @param path the root of the tree to delete
     */
    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.atWarn().setCause(e).log("Failed to delete {}", p);
                    }
                });
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Failed to walk directory for deletion: {}", path);
        }
    }
}
