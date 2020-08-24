package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.common2.PlatformSpecificManifest;
import com.aws.iot.evergreen.packagemanager.models.Platform;
import com.aws.iot.evergreen.packagemanager.models.PlatformSpecificRecipe;
import com.aws.iot.evergreen.util.Exec;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;

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
import java.util.stream.Collectors;

public final class PlatformResolver {
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");
    private static final String ALL_KEYWORD = "all";
    private static final Set<String> SUPPORTED_PLATFORMS = Collections.unmodifiableSet(initializeSupportedPlatforms());
    private static final Logger logger = LogManager.getLogger(PlatformResolver.class);
    public static final AtomicReference<Map<String, Integer>> RANKS =
            new AtomicReference<>(Collections.unmodifiableMap(initializeRanks()));

    public static final Platform CURRENT_PLATFORM = initializePlatformInfo();

    private static Platform initializePlatformInfo() {
        try {
            return getPlatformInfo();
        } catch (InterruptedException | IOException e) {
            // TODO: Better err handling
            logger.atError().setCause(e).log("Fail to read platform info");
            return null;
        }
    }

    private PlatformResolver() {
    }

    private static Set<String> initializeSupportedPlatforms() {
        Set<String> platforms = new HashSet<>();
        platforms.addAll(Arrays
                .asList("all", "any", "unix", "posix", "linux", "debian", "windows", "fedora", "ubuntu", "macos",
                        "raspbian", "qnx", "cygwin", "freebsd", "solaris", "sunos"));
        return platforms;
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private static Map<String, Integer> initializeRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: use better way to determine if a field is platform specific. Eg: using 'platform$' prefix.
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash")) || Files.exists(Paths.get("/usr/bin/bash"))) {
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

    private static Platform getPlatformInfo() throws IOException, InterruptedException {
        Platform.PlatformBuilder result = Platform.builder();

        if (isWindows) {
            result.os(Platform.OS_WINDOWS);
            result.osVersion(System.getProperty("os.version"));
        } else if (RANKS.get().containsKey("linux")) {
            result.os(Platform.OS_linux);
            result.osFlavor(RANKS.get().entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse(null));
            result.osVersion(System.getProperty("os.version"));
            // TODO:  get os release version. Eg: Ubuntu version will be output of "lsb_release -a "

        } else if (RANKS.get().containsKey("darwin")) {
            result.os(Platform.OS_Darwin);
            result.osFlavor(RANKS.get().entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse(null));
            result.osVersion(System.getProperty("os.version"));
        } else {
            // UNKNOWN OS
            logger.atWarn().kv("osName", System.getProperty("os.name")).kv("osName", System.getProperty("os.name"))
                    .log("Unrecognized OS");
        }

        String arch = System.getProperty("os.arch").toLowerCase();
        if ("x86_64".equals(arch)) {
            arch = "amd64"; // x86_64 & amd64 are same
        }
        result.architecture(arch);
        // TODO: find architecture variant

        return result.build();
    }

    /**
     * get closest platform.
     *
     * @param recipeList a list of recipe input
     * @return closest recipe
     */
    public static Optional<PlatformSpecificManifest> findBestMatch(List<PlatformSpecificManifest> recipeList) {
        // TODO to be implemented
        return Optional.empty();
    }


//    /**
//     * get closest platform.
//     *
//     * @param recipeList a list of recipe input
//     * @return closest recipe
//     */
//    public static Optional<PlatformSpecificRecipe> findBestMatch(List<PlatformSpecificRecipe> recipeList) {
//        return findBestMatch(CURRENT_PLATFORM, recipeList);
//    }

    /**
     * find best match from a list of recipes.
     *
     * @param currentPlatform the platform detail
     * @param recipeList      a list of recipe input
     * @return closest recipe
     */
    public static Optional<PlatformSpecificRecipe> findBestMatch(Platform currentPlatform,
                                                                 List<PlatformSpecificRecipe> recipeList) {
        List<String> architecturesToCheck;
        if (currentPlatform.getArchitecture() != null) {
            architecturesToCheck = Arrays.asList(currentPlatform.getArchitecture(), ALL_KEYWORD);
        } else {
            architecturesToCheck = Collections.singletonList(ALL_KEYWORD);
        }

        for (String arch : architecturesToCheck) {
            // TODO: match architecture variant
            // filter matching architecture
            List<PlatformSpecificRecipe> candidateRecipes = recipeList.stream().filter(r -> {
                if (!"all".equals(arch)) {
                    return arch.equals(r.getPlatform().getArchitecture());
                }
                return arch.equals(r.getPlatform().getArchitecture()) || r.getPlatform().getArchitecture() == null;
            }).collect(Collectors.toList());

            // try match os flavor

            if (currentPlatform.getOsFlavor() != null) {
                Optional<PlatformSpecificRecipe> recipe = candidateRecipes.stream()
                        .filter(r -> currentPlatform.getOsFlavor().equals(r.getPlatform().getOsFlavor()))
                        // TODO: filter version match and find best version match
                        .filter(r -> StringUtils.isBlank(r.getPlatform().getOsVersion()) ||
                                new Semver(currentPlatform.getOsVersion())
                                .satisfies(Requirement.buildNPM(r.getPlatform().getOsVersion()))).findFirst();
                if (recipe.isPresent()) {
                    return recipe;
                }
            }

            // try match os if no flavor match is found
            candidateRecipes = candidateRecipes.stream()
                    .filter(r -> r.getPlatform().getOsFlavor() == null || r.getPlatform().getOsFlavor()
                            .equals(ALL_KEYWORD)).collect(Collectors.toList());
            if (currentPlatform.getOs() != null) {
                Optional<PlatformSpecificRecipe> recipe =
                        candidateRecipes.stream().filter(r -> currentPlatform.getOs().equals(r.getPlatform().getOs()))
                                // TODO: filter version match and find best version match
                                .findFirst();
                if (recipe.isPresent()) {
                    return recipe;
                }
            }

            // if no os/osFlavor matched, try to match generic one
            Optional<PlatformSpecificRecipe> recipe = candidateRecipes.stream()
                    .filter(r -> r.getPlatform().getOs() == null || r.getPlatform().getOs().equals(ALL_KEYWORD))
                    // TODO: check version or not? It will be weird if a recipe doesn't has OS but has OS version
                    .findFirst();
            if (recipe.isPresent()) {
                return recipe;
            }
        }

        return Optional.empty();
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

    public static Object resolvePlatform(Map<Object, Object> input) {
        return resolvePlatform(RANKS.get(), input);
    }

    private static Object resolvePlatform(Map<String, Integer> ranks, Map<Object, Object> input) {
        int bestrank = -1;
        boolean platformResolved = false;
        Object bestRankNode = null;

        // assuming that either All or None of the children nodes are platform specific fields.
        for (Map.Entry<Object, Object> entry : input.entrySet()) {
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
            Map<Object, Object> outputMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : input.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Object resolvedValue = resolvePlatform(ranks, (Map<Object, Object>) entry.getValue());
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
            return resolvePlatform((Map<Object, Object>) bestRankNode);
        }
        return bestRankNode;
        */
    }
}
