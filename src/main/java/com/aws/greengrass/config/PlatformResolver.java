/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.Platform.Architecture;
import com.amazon.aws.iot.greengrass.component.common.Platform.OS;
import com.amazon.aws.iot.greengrass.component.common.PlatformHelper;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
public final class PlatformResolver {
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");
    private static final Set<String> SUPPORTED_PLATFORMS = Collections.unmodifiableSet(initializeSupportedPlatforms());
    private static final Logger logger = LogManager.getLogger(PlatformResolver.class);
    public static final AtomicReference<Map<String, Integer>> RANKS =
            new AtomicReference<>(Collections.unmodifiableMap(initializeRanks()));

    public static final Platform CURRENT_PLATFORM = initializePlatformInfo();

    private static Platform initializePlatformInfo() {
        try {
            return Platform.builder()
                    .os(getOSInfo())
                    .architecture(getArchInfo())
                    .build();
        } catch (InterruptedException | IOException e) {
            // TODO: Better err handling
            logger.atError().setCause(e).log("Fail to read platform info");
            return Platform.builder()
                    .os(OS.ALL)
                    .architecture(Architecture.ALL)
                    .build();
        }
    }

    private PlatformResolver() {
    }

    private static Set<String> initializeSupportedPlatforms() {
        return new HashSet<>(
                Arrays.asList("all", "any", "unix", "posix", "linux", "debian", "windows", "fedora", "ubuntu", "macos",
                        "raspbian", "qnx", "cygwin", "freebsd", "solaris", "sunos"));
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    @Deprecated
    private static Map<String, Integer> initializeRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: use better way to determine if a field is platform specific. Eg: using 'platform$' prefix.
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/sh")) || Files.exists(Paths.get("/bin/bash"))
                || Files.exists(Paths.get("/usr/bin/bash"))) {
            ranks.put("unix", 3);
            ranks.put("posix", 3);
        }
        if (Files.exists(Paths.get("/proc"))) {
            ranks.put("linux", 10);
        }
        if (Files.exists(Paths.get("/usr/bin/apt-get"))) {
            ranks.put("debian", 11);
        }
        if (isWindows) {
            ranks.put("windows", 5);
        }
        if (Files.exists(Paths.get("/usr/bin/yum"))) {
            ranks.put("fedora", 11);
        }
        if (!isWindows) {
            try {
                String sysver = Exec.sh("uname -a").toLowerCase();
                if (sysver.contains("ubuntu")) {
                    ranks.put("ubuntu", 20);
                }
                if (sysver.contains("darwin")) {
                    ranks.put("darwin", 10);
                    // TODO: currently we assume darwin is MacOS
                    ranks.put("macos", 20);
                }
                if (sysver.contains("raspbian")) {
                    ranks.put("raspbian", 22);
                }
                if (sysver.contains("qnx")) {
                    ranks.put("qnx", 22);
                }
                if (sysver.contains("cygwin")) {
                    ranks.put("cygwin", 22);
                }
                if (sysver.contains("freebsd")) {
                    ranks.put("freebsd", 22);
                }
                if (sysver.contains("solaris") || sysver.contains("sunos")) {
                    ranks.put("solaris", 22);
                }
            } catch (InterruptedException | IOException e) {
                logger.atError().log("Error while running uname -a");
            }
        }
        return ranks;
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private static OS getOSInfo() throws IOException, InterruptedException {
        if (isWindows) {
            return OS.WINDOWS;
        }

        // TODO: use UNRECOGNIZED instead.
        OS currentOS = OS.ALL;
        String sysver = Exec.sh("uname -a").toLowerCase();

        if (sysver.contains("darwin")) {
            currentOS = OS.DARWIN;
        }
        if (Files.exists(Paths.get("/proc"))) {
            currentOS = OS.LINUX;
        }

        /*
        TODO: since B1 release only support Linux platform.
        Temporarily stop platform detection after Linux.

        if (Files.exists(NucleusPaths.get("/usr/bin/yum"))) {
            currentOS = findMoreSpecificOS(currentOS, OS.FEDORA);
        }
        if (Files.exists(NucleusPaths.get("/usr/bin/apt-get"))) {
            currentOS = findMoreSpecificOS(currentOS, OS.DEBIAN);
        }
        if (sysver.contains("raspbian") || sysver.contains("raspberry")) {
            currentOS = findMoreSpecificOS(currentOS, OS.RASPBIAN);
        }
        if (sysver.contains("ubuntu")) {
            currentOS = findMoreSpecificOS(currentOS, OS.UBUNTU);
        }

        */

        return currentOS;
    }

    private static Architecture getArchInfo() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if ("x86_64".equals(arch) || "amd64".equals(arch)) {
            return Architecture.AMD64; // x86_64 & amd64 are same
        }
        if ("i386".equals(arch) || "x86".equals(arch)) {
            return Architecture.X86;
        }
        if (arch.contains("arm")) {
            return Architecture.ARM;
        }
        if ("aarch64".equals(arch)) {
            return Architecture.AARCH64;
        }
        // TODO: use UNRECOGNIZED instead.
        return Architecture.ALL;
    }

    /**
     * get closest platform from a list of manifests.
     *
     * @param recipeList a list of recipe input
     * @return closest recipe
     */
    public static Optional<PlatformSpecificManifest> findBestMatch(List<PlatformSpecificManifest> recipeList) {
        return PlatformHelper.findBestMatch(CURRENT_PLATFORM, recipeList);
    }

    /**
     * Get the most specific platform string for the current system.
     *
     * @return platform
     */
    @Deprecated
    public static String getPlatform() {
        return RANKS.get().entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey)
                .orElse("all");
    }

    @Deprecated // Still used in source code for existing tests that use multi-platform config.yaml when kernel starts.
    // TODO Remove from source code and put into test utils
    public static Object resolvePlatform(Map<String, Object> input) {
        return resolvePlatform(RANKS.get(), input);
    }

    private static Object resolvePlatform(Map<String, Integer> ranks, Map<String, Object> input) {
        int bestrank = -1;
        boolean platformResolved = false;
        Object bestRankNode = null;

        // assuming that either All or None of the children nodes are platform specific fields.
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!SUPPORTED_PLATFORMS.contains(entry.getKey())) {
                continue;
            }
            platformResolved = true;
            Integer g = ranks.get(entry.getKey());
            if (g != null && g > bestrank) {
                bestrank = g;
                bestRankNode = entry.getValue();
            }
        }
        if (!platformResolved) {
            Map<String, Object> outputMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Object resolvedValue = resolvePlatform(ranks, (Map<String, Object>) entry.getValue());
                    if (resolvedValue != null) {
                        outputMap.put(entry.getKey(), resolvedValue);
                    }
                } else {
                    outputMap.put(entry.getKey(), entry.getValue());
                }
            }
            return outputMap;
        }
        // assume no nested platform specific configurations.
        return bestRankNode;

        // if nested platform specific node is allowed, use below code.
        // Can have validation on the ranks so that inner node rank can't exceed outer node rank.
        /*
        if (bestRankNode == null) {
            return null;
        }
        if (bestRankNode instanceof Map) {
            return resolvePlatform((Map<String, Object>) bestRankNode);
        }
        return bestRankNode;
        */
    }

}
