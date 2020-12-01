/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.PlatformHelper;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
public class PlatformResolver {
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");
    public static final String ALL_KEYWORD = "all";
    public static final String UNKNOWN_KEYWORD = "unknown";

    private static final Logger logger = LogManager.getLogger(PlatformResolver.class);

    public static final String OS_KEY = "os";
    public static final String ARCHITECTURE_KEY = "architecture";
    public static final String ARCHITECTURE_DETAIL_KEY = "architecture.detail";

    // Note that this is not an exhaustive list of OSes, but happens to be a set of platforms detected.
    public static final String OS_WINDOWS = "windows";
    public static final String OS_DARWIN = "darwin";
    public static final String OS_LINUX = "linux";

    // Note that this is not an exhaustive list of Architectures, but happens to be a set of platforms detected.
    public static final String ARCH_AMD64 = "amd64";
    public static final String ARCH_X86 = "x86";
    public static final String ARCH_ARM = "arm";
    public static final String ARCH_AARCH64 = "aarch64";

    private final DeviceConfiguration deviceConfiguration;

    private static final AtomicReference<Platform> DETECTED_PLATFORM =
            new AtomicReference<>(initializePlatform());

    private static Platform initializePlatform() {
        return Platform.builder()
                .add(OS_KEY, getOSInfo())
                .add(ARCHITECTURE_KEY, getArchInfo())
                .add(ARCHITECTURE_DETAIL_KEY, getArchDetailInfo())
                .build();
    }

    @Inject
    public PlatformResolver(DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
    }

    /**
     * Get current platform.
     * Detect current platform and apply device configuration override.
     *
     * @return Platform key-value map
     */
    public Map<String, String> getCurrentPlatform() {
        Map<String, String> detected = DETECTED_PLATFORM.get();
        if (deviceConfiguration == null) {
            return detected;
        }
        Topics platformOverride = deviceConfiguration.getPlatformOverrideTopic();
        if (platformOverride == null) {
            return detected;
        }
        Map<String, String> platform = new HashMap<>(detected);
        for (Map.Entry<String, Object> entry: platformOverride.toPOJO().entrySet()) {
            if (entry.getValue() instanceof String) {
                // platform doesn't support Map/List value
                platform.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return platform;
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private static String getOSInfo() {
        if (isWindows) {
            return OS_WINDOWS;
        }
        try {
            String sysver = Exec.sh("uname -a").toLowerCase();

            if (sysver.contains("darwin")) {
                return OS_DARWIN;
            }
            if (Files.exists(Paths.get("/proc"))) {
                return OS_LINUX;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error trying to determine OS", e);
        }
        return UNKNOWN_KEYWORD;
    }

    private static String getArchInfo() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if ("x86_64".equals(arch) || "amd64".equals(arch)) {
            return ARCH_AMD64; // x86_64 & amd64 are same
        }
        if ("i386".equals(arch) || "x86".equals(arch)) {
            return ARCH_X86;
        }
        if (arch.contains("arm")) {
            return ARCH_ARM;
        }
        if ("aarch64".equals(arch)) {
            return ARCH_AARCH64;
        }
        return UNKNOWN_KEYWORD;
    }

    private static String getArchDetailInfo() {
        if (isWindows) {
            return null;
        }
        try {
            String archDetail = Exec.sh("uname -m").toLowerCase();
            // TODO: "uname -m" is not sufficient to capture arch details on all platforms.
            // Currently only return if detected arm, as required by lambda launcher.
            if ("armv6l".equals(archDetail) || "armv7l".equals(archDetail) || "armv8l".equals(archDetail)) {
                return archDetail;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error trying to determine architecture detail - assuming not available", e);
        }
        return null;
    }

    /**
     * get closest platform from a list of manifests.
     *
     * @param recipeList a list of recipe input
     * @return closest recipe
     */
    public Optional<PlatformSpecificManifest> findBestMatch(List<PlatformSpecificManifest> recipeList) {
        return PlatformHelper.findBestMatch(getCurrentPlatform(), recipeList);
    }

    /**
     * Filters lifecycle (or any other section). Input set of allowed platform keywords, and selectors
     * that are being applied in priority order. Configuration is not assumed to be deep. Note, nulls
     * are treated as "no value".
     *
     * @param source     Source configuration (e.g. lifecycle section)
     * @param keywords   Set of platform keywords that are filtered. Assumed to contain ALL keyword
     * @param selectors  Set of selectors in priority order. ALL keyword is optional.
     * @return filtered config, or empty if removed through filtering
     */
    public static Optional<Object> filterPlatform(Map<String, Object> source, Set<String> keywords,
                                         List<String> selectors) {

        //
        // Trying to preserve nulls will make this logic much more difficult than it is. Since this is
        // (currently) exclusively used for lifecycle, it's fine to treat null as if a value is missing.
        //
        if (source.keySet().stream().anyMatch(keywords::contains)) {
            // Selectors are provided in priority order with highest priority selector first.
            // Find the first selector (if any) that has a match at this level of the multi-level map.
            Optional<Object> selected = selectors == null ? Optional.empty() :
                    selectors.stream().map(source::get)
                            .filter(Objects::nonNull)
                            .findFirst();
            if (!selected.isPresent()) {
                // consider ALL keyword (this may not be in list of selectors).
                // Note, do not confuse this with set of keywords which does include "ALL".
                // Same as adding "ALL" to list, without mutating or duplicating list
                selected = Optional.ofNullable(source.get(ALL_KEYWORD));
            }
            // diagnostic reporting even if selected was successful
            checkForBadSelectorMap(source, keywords, selected);
            if (selected.isPresent()) {
                // recursively apply, allowing more specific or overlapping keywords
                // All other keys (lower priority or default) are effectively erased.
                return filterPlatformIfMap(selected.get(), keywords, selectors);
            }
            // desired selector not found. Caller needs to establish default
            return Optional.empty();
        }
        // recursively filter platform
        return Optional.of(source.entrySet().stream()
                .map(e -> filterPlatformMapEntry(e, keywords, selectors))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue)));
    }

    /**
     * Diagnostic reporting for bad selector section.
     * @param source Source map
     * @param keywords Set of valid selector keywords (including ALL)
     * @param selected Selected section if any, else empty.
     */
    private static void checkForBadSelectorMap(Map<String, Object> source, Set<String> keywords,
                                               Optional<Object> selected) {
        List<String> notSelector = source.keySet().stream()
                .filter(k -> !keywords.contains(k)).collect(Collectors.toList());
        if (!notSelector.isEmpty()) {
            List<String> yesSelector =
                    source.keySet().stream().filter(k -> !keywords.contains(k)).collect(Collectors.toList());
            logger.atError()
                    .kv("selectors", yesSelector)
                    .kv("nonSelectors", notSelector)
                    .kv("selected", selected.orElse(null))
                    .log("Configuration section contains both selector and non-selector keys at same level. "
                            + "Non-selector keys are ignored.");
        }
    }

    /**
     * Givenn a key/value pair of a map, filter the platform recursively.
     * @param entry      Key/Value pair
     * @param keywords   Set of platform keywords that are filtered. Assumed to contain ALL keyword
     * @param selectors  Set of selectors in priority order. ALL keyword is optional.
     * @return new key/value pair, or null if platform filtering eliminated value
     */
    private static Map.Entry<String,Object> filterPlatformMapEntry(Map.Entry<String,Object> entry, Set<String> keywords,
                                                                   List<String> selectors) {
        Optional<Object> v = filterPlatformIfMap(entry.getValue(), keywords, selectors);
        if (v.isPresent()) {
            return new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), v.get());
        } else {
            return null;
        }
    }

    /**
     * Filters lifecycle (or any other section). Source is unknown to be a map.
     *
     * @param source     Source configuration (e.g. lifecycle section)
     * @param keywords   Set of platform keywords that are filtered. Assumed to contain ALL keyword
     * @param selectors  Set of selectors in priority order. ALL keyword is optional.
     * @return filtered config, or empty()
     */
    private static Optional<Object> filterPlatformIfMap(Object source, Set<String> keywords,
                                              List<String> selectors) {
        if (source instanceof Map) {
            return filterPlatform((Map<String, Object>) source, keywords, selectors);
        } else {
            // non-maps are leaf values (note, null value is treated as missing)
            return Optional.ofNullable(source);
        }
    }

}
