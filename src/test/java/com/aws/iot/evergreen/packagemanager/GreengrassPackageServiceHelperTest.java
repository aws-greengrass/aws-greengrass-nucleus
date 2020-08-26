package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentResult;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class GreengrassPackageServiceHelperTest {

    @Mock
    private AWSEvergreen client;

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
    void GIVEN_component_name_version_WHEN_download_component_recipe_THEN_task_succeed() throws Exception {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        ByteBuffer testRecipeBytes = ByteBuffer.wrap(recipeContents.getBytes());
        GetComponentResult testResult = new GetComponentResult().withRecipe(testRecipeBytes);
        doReturn(testResult).when(client).getComponent(getComponentRequestArgumentCaptor.capture());
        String downloadPackageRecipeAsString =
                helper.downloadPackageRecipeAsString(new PackageIdentifier(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                                   new Semver("1.0.0"), "private"));

        assertEquals(recipeContents, downloadPackageRecipeAsString);
    }

    // TODO: Add test cases for failure status codes once the SDK model is updated to return proper http responses

    @Test
    void GIVEN_recipe_file_WHEN_create_component_THEN_upload_the_recipe() throws Exception {
        ArgumentCaptor<CreateComponentRequest> createComponentRequestArgumentCaptor =
                ArgumentCaptor.forClass(CreateComponentRequest.class);
        CreateComponentResult mockResult = new CreateComponentResult();
        doReturn(mockResult).when(client).createComponent(createComponentRequestArgumentCaptor.capture());
        Path recipePath = TestHelper.getRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        GreengrassPackageServiceHelper.createComponent(client, recipePath);

        CreateComponentRequest createComponentRequest = createComponentRequestArgumentCaptor.getValue();
        assertEquals(recipePath.toFile().length(), createComponentRequest.getRecipe().limit());
    }

    @Test
    void GIVEN_component_name_version_WHEN_delete_component_THEN_send_service_request() {
        ArgumentCaptor<DeleteComponentRequest> requestCaptor = ArgumentCaptor.forClass(DeleteComponentRequest.class);
        GreengrassPackageServiceHelper.deleteComponent(client, "mockName", "mockVersion");
        verify(client, times(1)).deleteComponent(requestCaptor.capture());
        DeleteComponentRequest request = requestCaptor.getValue();
        assertEquals("mockName", request.getComponentName());
        assertEquals("mockVersion", request.getComponentVersion());
    }
}
