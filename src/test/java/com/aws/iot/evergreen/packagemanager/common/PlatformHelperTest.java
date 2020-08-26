package com.aws.iot.evergreen.packagemanager.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


public class PlatformHelperTest {

    @Test
    public void GIVEN_platform_WHEN_getClosestPlatform_THEN_correct_recipe_returned() throws Exception {
        Platform platformToTest = Platform.builder()
                .os(PlatformHelper.OS_UBUNTU.getName())
                .architecture(PlatformHelper.ARCH_AMD64.getName())
                .build();

        PlatformSpecificManifest recipeCandidate1 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.ARCH_AMD64.getName())
                .os(PlatformHelper.OS_UBUNTU.getName())
                .build());

        PlatformSpecificManifest recipeCandidate2 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.ARCH_AMD64.getName())
                .os(PlatformHelper.OS_LINUX.getName())
                .build());

        PlatformSpecificManifest recipeCandidate3 = createRecipeForPlatform(Platform.builder()
                .os(PlatformHelper.OS_UBUNTU.getName())
                .build());

        PlatformSpecificManifest recipeCandidate4 = createRecipeForPlatform(Platform.builder()
                .os(PlatformHelper.OS_DEBIAN.getName())
                .architecture(Platform.ALL_KEYWORD)
                .build());

        PlatformSpecificManifest recipeCandidate5 = createRecipeForPlatform(Platform.builder()
                .os(PlatformHelper.OS_LINUX.getName())
                .build());

        PlatformSpecificManifest recipeCandidate6 = createRecipeForPlatform(null);

        PlatformSpecificManifest recipeCandidate_notApplicable1 = createRecipeForPlatform(Platform.builder()
                .os(PlatformHelper.OS_WINDOWS.getName())
                .build());
        PlatformSpecificManifest recipeCandidate_notApplicable2 = createRecipeForPlatform(Platform.builder()
                .architecture(PlatformHelper.ARCH_ARM.getName())
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


    private PlatformSpecificManifest createRecipeForPlatform(Platform platform) {
        return PlatformSpecificManifest.builder()
                .platform(platform).build();
    }
}
