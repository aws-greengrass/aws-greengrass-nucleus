package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;

import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;

import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.model.GetComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.GetComponentResult;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class GreengrassPackageServiceHelperTest {

    @Mock
    private AWSGreengrassComponentManagement client;

    @Mock
    private GreengrassPackageServiceClientFactory clientFactory;

    private GreengrassPackageServiceHelper helper;

    @Captor
    private ArgumentCaptor<GetComponentRequest> getComponentRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        when(clientFactory.getCmsClient()).thenReturn(client);
        this.helper = spy(new GreengrassPackageServiceHelper(clientFactory));
    }

    @Test
    void GIVEN_artifact_url_WHEN_attempt_download_THEN_task_succeed() throws Exception {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        ByteBuffer testRecipeBytes = ByteBuffer.wrap(recipeContents.getBytes());
        GetComponentResult testResult = new GetComponentResult().withRecipe(testRecipeBytes);
        doReturn(testResult).when(client).getComponent(getComponentRequestArgumentCaptor.capture());
        PackageRecipe testPackage =
                helper.downloadPackageRecipe(new PackageIdentifier(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                                   new Semver("1.0.0")));

        GetComponentRequest generatedRequest = getComponentRequestArgumentCaptor.getValue();
        assertEquals(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, generatedRequest.getComponentName());
        assertEquals("1.0.0", generatedRequest.getComponentVersion());
        assertEquals("YAML", generatedRequest.getType());
        assertEquals(testPackage.getPackageName(), TestHelper.MONITORING_SERVICE_PACKAGE_NAME);
        assertTrue(testPackage.getVersion().isEqualTo("1.0.0"));
    }

    // TODO: Add test cases for failure status codes once the SDK model is updated to return proper http responses
}
