package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.CommitComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentArtifactUploadUrlRequest;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentResult;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
        PackageRecipe testPackage =
                helper.downloadPackageRecipe(new PackageIdentifier(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                                   new Semver("1.0.0"), "private"));

        GetComponentRequest generatedRequest = getComponentRequestArgumentCaptor.getValue();
        assertEquals(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, generatedRequest.getComponentName());
        assertEquals("1.0.0", generatedRequest.getComponentVersion());
        assertEquals("YAML", generatedRequest.getType());
        assertEquals("private", generatedRequest.getScope());
        assertEquals(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, testPackage.getComponentName());
        assertEquals(new Semver("1.0.0"), testPackage.getVersion());
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
    void GIVEN_a_non_regular_file_WHEN_upload_as_artifact_THEN_skip_file_upload() throws Exception {
        Path artifactPath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        GreengrassPackageServiceHelper.createAndUploadComponentArtifact(client, artifactPath.toFile(), TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        verify(client, times(0)).createComponentArtifactUploadUrl(any());
    }

    @Test
    void GIVEN_upload_url_WHEN_upload_component_artifact_THEN_upload_file_successfully() throws Exception {
        URL mockUrl = mock(URL.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HttpURLConnection mockConn = mock(HttpURLConnection.class);
        doNothing().when(mockConn).setDoOutput(eq(true));
        doNothing().when(mockConn).setRequestMethod(any());
        doNothing().when(mockConn).connect();
        doReturn(baos).when(mockConn).getOutputStream();
        doReturn(mockConn).when(mockUrl).openConnection();

        File artifactFile = TestHelper.getArtifactForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0",
                "monitor_artifact_100.txt").toFile();
        GreengrassPackageServiceHelper.uploadComponentArtifact(mockUrl, artifactFile);
        assertEquals(artifactFile.length(), baos.size());
    }

    @Test
    void GIVEN_component_name_version_and_artifact_name_WHEN_create_artifact_upload_url_THEN_send_service_request() {
        ArgumentCaptor<CreateComponentArtifactUploadUrlRequest> requestCaptor = ArgumentCaptor.forClass(CreateComponentArtifactUploadUrlRequest.class);
        GreengrassPackageServiceHelper.createComponentArtifactUploadUrl(client, "mockName", "mockVersion",
                "mockArtifact");
        verify(client, times(1)).createComponentArtifactUploadUrl(requestCaptor.capture());
        CreateComponentArtifactUploadUrlRequest request = requestCaptor.getValue();
        assertEquals("mockName", request.getComponentName());
        assertEquals("mockVersion", request.getComponentVersion());
        assertEquals("mockArtifact", request.getArtifactName());
    }

    @Test
    void GIVEN_component_name_version_WHEN_commit_component_THEN_send_service_request() {
        ArgumentCaptor<CommitComponentRequest> requestCaptor = ArgumentCaptor.forClass(CommitComponentRequest.class);
        GreengrassPackageServiceHelper.commitComponent(client, "mockName", "mockVersion");
        verify(client, times(1)).commitComponent(requestCaptor.capture());
        CommitComponentRequest request = requestCaptor.getValue();
        assertEquals("mockName", request.getComponentName());
        assertEquals("mockVersion", request.getComponentVersion());
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
