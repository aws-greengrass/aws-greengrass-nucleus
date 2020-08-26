package com.aws.iot.evergreen.packagemanager.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.packagemanager.common.Platform.ALL_KEYWORD;

public final class PlatformHelper {

    public static final Collection<OS> OS_SUPPORTED = OS.ALL.getChildrenRecursively();

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
        List<String> architecturesToCheck;
        if (currentPlatform.getArchitecture() == null) {
            architecturesToCheck = Collections.singletonList(ALL_KEYWORD);
        } else {
            architecturesToCheck = Arrays.asList(currentPlatform.getArchitecture(), ALL_KEYWORD);
        }

        for (String arch : architecturesToCheck) {
            // filter matching architecture
            List<PlatformSpecificManifest> candidateRecipes = recipeList.stream().filter(r -> {
                if (!ALL_KEYWORD.equalsIgnoreCase(arch)) {
                    return r.getPlatform() != null && arch.equalsIgnoreCase(r.getPlatform().getArchitecture());
                }
                return r.getPlatform() == null
                        || r.getPlatform().getArchitecture() == null
                        || ALL_KEYWORD.equalsIgnoreCase(r.getPlatform().getArchitecture());
            }).collect(Collectors.toList());

            // match os in rank
            OS currentOS = OS_SUPPORTED.stream()
                    .filter(r -> r.getName().equalsIgnoreCase(currentPlatform.getOs()))
                    .findFirst().orElse(null);

            while (currentOS != null && !OS.ALL.equals(currentOS)) {
                OS osToCheck = currentOS;
                Optional<PlatformSpecificManifest> recipe = candidateRecipes.stream()
                        .filter(r -> r.getPlatform() != null
                                && osToCheck.getName().equalsIgnoreCase(r.getPlatform().getOs()))
                        // TODO: filter version match
                        .findFirst();
                if (recipe.isPresent()) {
                    return recipe;
                }
                // if can't find match for current OS, search a more generic one
                currentOS = currentOS.parent;
            }

            // if no match find, try to match the 'all'.
            Optional<PlatformSpecificManifest> recipe = candidateRecipes.stream()
                    .filter(r -> r.getPlatform() == null || r.getPlatform().getOs() == null
                            || r.getPlatform().getOs().equalsIgnoreCase(ALL_KEYWORD))
                    // TODO: filter version match
                    .findFirst();

            if (recipe.isPresent()) {
                return recipe;
            }
        }

        return Optional.empty();
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

    /**
     * Non customer-facing class. Keeps the OS hierarchy data.
     */
    @Getter
    public enum OS {
        ALL(null, ALL_KEYWORD),
        WINDOWS(ALL, "windows"),
        UNIX(ALL, "unix"),
        LINUX(UNIX, "linux"),
        FEDORA(LINUX, "fedora"),
        DEBIAN(LINUX, "debian"),
        UBUNTU(DEBIAN, "ubuntu"),
        RASPBIAN(DEBIAN, "raspbian"),
        DARWIN(UNIX, "darwin"),
        MAC_OS(DARWIN, "macOS");

        private final OS parent;
        private final String name;
        private final Collection<OS> children;
        private final int rank;

        OS(OS parent, String name) {
            this.parent = parent;
            this.name = name;
            this.children = new HashSet<>();
            if (parent == null) {
                this.rank = 0;
            } else {
                this.rank = parent.getRank() + 1;
                parent.getChildren().add(this);
            }
        }

        Set<OS> getChildrenRecursively() {
            Set<OS> result = new HashSet<>(children);
            for (OS child: children) {
                result.addAll(child.getChildrenRecursively());
            }
            return result;
        }
    }

    /**
     * Non customer-facing class. Currently only has name field.
     */
    @Getter
    @AllArgsConstructor
    public enum Architecture {
        ALL(ALL_KEYWORD), AMD64("amd64"), ARM("arm");
        private final String name;
    }
}
