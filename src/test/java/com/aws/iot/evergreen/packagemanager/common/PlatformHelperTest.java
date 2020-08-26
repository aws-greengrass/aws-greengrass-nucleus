package com.aws.iot.evergreen.packagemanager.common;

import com.aws.iot.evergreen.packagemanager.common.PlatformHelper.OS;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


public class PlatformHelperTest {

    @Test
    public void GIVEN_platform_WHEN_findBestMatch_THEN_correct_recipe_returned() throws Exception {
        Platform platformToTest = Platform.builder()
                .os(OS.UBUNTU.getName())
                .architecture(PlatformHelper.Architecture.AMD64.getName())
                .build();

        PlatformSpecificManifest recipeCandidate1 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.Architecture.AMD64.getName())
                .os(OS.UBUNTU.getName())
                .build());

        PlatformSpecificManifest recipeCandidate2 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.Architecture.AMD64.getName())
                .os(OS.LINUX.getName())
                .build());

        PlatformSpecificManifest recipeCandidate3 = createRecipeForPlatform(Platform.builder()
                .os(OS.UBUNTU.getName())
                .build());

        PlatformSpecificManifest recipeCandidate4 = createRecipeForPlatform(Platform.builder()
                .os(OS.DEBIAN.getName())
                .architecture(Platform.ALL_KEYWORD)
                .build());

        PlatformSpecificManifest recipeCandidate5 = createRecipeForPlatform(Platform.builder()
                .os(OS.LINUX.getName())
                .build());

        PlatformSpecificManifest recipeCandidate6 = createRecipeForPlatform(null);

        PlatformSpecificManifest recipeCandidate_notApplicable1 = createRecipeForPlatform(Platform.builder()
                .os(OS.WINDOWS.getName())
                .build());
        PlatformSpecificManifest recipeCandidate_notApplicable2 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.Architecture.ARM.getName())
                .build());

        Optional<PlatformSpecificManifest> result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate1,
                recipeCandidate2,
                recipeCandidate3,
                recipeCandidate4,
                recipeCandidate5,
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate1, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate2,
                recipeCandidate3,
                recipeCandidate4,
                recipeCandidate5,
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate2, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate3,
                recipeCandidate4,
                recipeCandidate5,
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate3, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate4,
                recipeCandidate5,
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate4, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate5,
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate5, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate6,
                recipeCandidate_notApplicable1));

        assertTrue(result.isPresent());
        assertEquals(recipeCandidate6, result.get());

        result = PlatformHelper.findBestMatch(platformToTest, Arrays.asList(
                recipeCandidate_notApplicable1,
                recipeCandidate_notApplicable2));

        assertFalse(result.isPresent());
    }

    @Test
    public void GIVEN_os_WHEN_findMoreSpecificOS_THEN_correct_OS_returned() throws Exception {
        assertEquals(OS.ALL, PlatformHelper.findMoreSpecificOS(null, OS.ALL));
        assertEquals(OS.ALL, PlatformHelper.findMoreSpecificOS(OS.ALL, null));
        assertEquals(OS.LINUX, PlatformHelper.findMoreSpecificOS(OS.ALL, OS.LINUX));
    }

    @Test
    public void GIVEN_os_WHEN_getChildrenRecursively_THEN_all_children_returned() throws Exception {
        assertEquals(OS.values().length - 1, OS.ALL.getChildrenRecursively().size());
    }

    private PlatformSpecificManifest createRecipeForPlatform(Platform platform) {
        return PlatformSpecificManifest.builder()
                .platform(platform).build();
    }
}
