package com.aws.iot.evergreen.packagemanager.common;

import com.aws.iot.evergreen.packagemanager.common.Platform.Architecture;
import com.aws.iot.evergreen.packagemanager.common.Platform.OS;

import java.util.List;
import java.util.Optional;

public final class PlatformHelper {

    private PlatformHelper() {
    }

    /**
     * find best match from a list of recipes.
     *
     * @param currentPlatform the platform detail
     * @param recipeList      a list of recipe input
     * @return closest recipe
     */
    public static Optional<PlatformSpecificManifest> findBestMatch(Platform currentPlatform,
                                                                   List<PlatformSpecificManifest> recipeList) {
        return recipeList.stream()
                .filter(r -> {
                    // match arch
                    if (r.getPlatform() == null || r.getPlatform().getArchitecture() == null
                            || r.getPlatform().getArchitecture().equals(Architecture.ALL)) {
                        return true;
                    }
                    Architecture currentArch = currentPlatform.getArchitecture();
                    while (!Architecture.ALL.equals(currentArch)) {
                        if (currentArch.equals(r.getPlatform().getArchitecture())) {
                            return true;
                        }
                        currentArch = currentArch.getParent();
                    }
                    return false;
                })
                .filter(r -> {
                    // match os
                    if (r.getPlatform() == null || r.getPlatform().getOs() == null
                        || r.getPlatform().getOs().equals(OS.ALL)) {
                        return true;
                    }
                    OS currentOS = currentPlatform.getOs();
                    while (!currentOS.equals(OS.ALL)) {
                        if (currentOS.equals(r.getPlatform().getOs())) {
                            return true;
                        }
                        currentOS = currentOS.getParent();
                    }
                    return false;
                })
                .max((o1, o2) -> {
                    if (o1.getPlatform() == null) {
                        return -1;
                    }
                    if (o2.getPlatform() == null) {
                        return 1;
                    }
                    // getPlatform().getArch() shouldn't return null after parsing from recipe
                    // Having null check just in case
                    Architecture o1Arch = getOrDefault(o1.getPlatform().getArchitecture(), Architecture.ALL);
                    Architecture o2Arch = getOrDefault(o2.getPlatform().getArchitecture(), Architecture.ALL);

                    if (o1Arch.getRank() != o2Arch.getRank()) {
                        return o1Arch.getRank() < o2Arch.getRank() ? -1 : 1;
                    }

                    OS o1Os = getOrDefault(o1.getPlatform().getOs(), OS.ALL);
                    OS o2Os = getOrDefault(o2.getPlatform().getOs(), OS.ALL);
                    return Integer.compare(o1Os.getRank(), o2Os.getRank());
                });
    }

    private static <T> T getOrDefault(T getValue, T defaultValue) {
        if (getValue == null) {
            return defaultValue;
        }
        return getValue;
    }

    /**
     * Find the higher rank (more specific one) between OS info. This doesn't check the two OS are on the same branch.
     * @param thisOS this
     * @param other other
     * @return higer rank OS
     */
    public static OS findMoreSpecificOS(OS thisOS, OS other) {
        if (thisOS == null) {
            return other;
        }
        if (other == null) {
            return thisOS;
        }
        if (other.getRank() > thisOS.getRank()) {
            return other;
        }
        return thisOS;
    }

}
