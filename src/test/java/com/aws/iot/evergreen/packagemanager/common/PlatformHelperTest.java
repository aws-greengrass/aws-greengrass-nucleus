package com.aws.iot.evergreen.packagemanager.common;

import com.aws.iot.evergreen.packagemanager.common.Platform.OS;
import com.aws.iot.evergreen.packagemanager.common.Platform.Architecture;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


public class PlatformHelperTest {

    @Test
    public void GIVEN_platform_WHEN_findBestMatch_THEN_correct_recipe_returned() throws Exception {
        Platform platformToTest = Platform.builder()
                .os(OS.UBUNTU)
                .architecture(Architecture.AMD64)
                .build();

        PlatformSpecificManifest recipeCandidate1 = createRecipeForPlatform(Platform.builder()
                .architecture(Architecture.AMD64)
                .os(OS.UBUNTU)
                .build());

        PlatformSpecificManifest recipeCandidate2 = createRecipeForPlatform(Platform.builder()
                .architecture(Architecture.AMD64)
                .os(OS.LINUX)
                .build());

        PlatformSpecificManifest recipeCandidate3 = createRecipeForPlatform(Platform.builder()
                .os(OS.UBUNTU)
                .build());

        PlatformSpecificManifest recipeCandidate4 = createRecipeForPlatform(Platform.builder()
                .os(OS.DEBIAN)
                .architecture(Architecture.ALL)
                .build());

        PlatformSpecificManifest recipeCandidate5 = createRecipeForPlatform(Platform.builder()
                .os(OS.LINUX)
                .build());

        PlatformSpecificManifest recipeCandidate6 = createRecipeForPlatform(null);

        PlatformSpecificManifest recipeCandidate_notApplicable1 = createRecipeForPlatform(Platform.builder()
                .os(OS.WINDOWS)
                .build());
        PlatformSpecificManifest recipeCandidate_notApplicable2 = createRecipeForPlatform(Platform.builder()
                .architecture(Architecture.ARM)
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

    private PlatformSpecificManifest createRecipeForPlatform(Platform platform) {
        return PlatformSpecificManifest.builder()
                .platform(platform).build();
    }
}
