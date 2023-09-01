/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.InvalidRequestException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_STACK_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_TYPES_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_MEMBERSHIP_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static java.util.Collections.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class CurrentDeploymentFinisherTest {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String TEST_UUID = "testDeploymentId";

    private static final String CONFIG_ARN_PLACEHOLDER = "TARGET_CONFIGURATION_ARN";
    private static final String TEST_CONFIGURATION_ARN =
            "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";

    private static final String GROUP_NAME = "thinggroup/group1";
    private static final String EXPECTED_ROOT_PACKAGE_NAME = "component1";
    private static final List<String> EXPECTED_ROOT_PACKAGE_LIST = Collections.singletonList("component1");

    private static Logger logger = LogManager.getLogger(CurrentDeploymentFinisher.class);

    @Mock
    protected DeploymentStatusKeeper deploymentStatusKeeper;
    @Mock
    protected DeploymentDirectoryManager deploymentDirectoryManager;
    protected Topics config = mock(Topics.class);
    @Mock
    private Kernel kernel;
    @Mock
    private GreengrassService mockGreengrassService;
    @Mock
    protected Context context;

    private CurrentDeploymentFinisher currentDeploymentFinisher;

    /**
     * Default deployment with id=1
     */
    private final Deployment deployment = new Deployment(
            getTestDeploymentDocument(),
            Deployment.DeploymentType.IOT_JOBS,
            TEST_JOB_ID_1);

    @BeforeEach
    void beforeEach() {
        logger.setLevel(String.valueOf(Level.DEBUG));
        currentDeploymentFinisher = new CurrentDeploymentFinisher(logger, deployment, deploymentStatusKeeper,
                deploymentDirectoryManager, config, kernel);
    }

    @Test
    void GIVEN_successful_deployment_result_WHEN_finishCurrentDeployment_THEN_deployment_completes_successfully()
            throws InterruptedException, InvalidRequestException, JsonProcessingException, ServiceLoadException {
        updateDeploymentObject(currentDeploymentFinisher);

        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);

        String expectedGroupName = GROUP_NAME;
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, expectedGroupName, allGroupTopics);

        Topics groupToLastDeploymentTopics = Topics.of(context, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null);
        Topics lastDeploymentGroupTopics = Topics.of(context, expectedGroupName, groupToLastDeploymentTopics);
        groupToLastDeploymentTopics.children.put(new CaseInsensitiveString(expectedGroupName), lastDeploymentGroupTopics);

        Topics groupMembershipTopics = Topics.of(context, GROUP_MEMBERSHIP_TOPICS, null);
        groupMembershipTopics.lookup(expectedGroupName);
        Topic pkgTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        Topic groupTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12");
        Topic groupNameTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME,
                expectedGroupName);
        Map<CaseInsensitiveString, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                pkgTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                groupTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                groupNameTopic1);
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.put(new CaseInsensitiveString(expectedGroupName), deploymentGroupTopics);

        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookupTopics(GROUP_TO_LAST_DEPLOYMENT_TOPICS)).thenReturn(groupToLastDeploymentTopics);
        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(groupMembershipTopics);


        when(kernel.locate(any())).thenReturn(mockGreengrassService);
        when(mockGreengrassService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);

        currentDeploymentFinisher.finishCurrentDeployment(result, false);

        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.SUCCEEDED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));

    }

    @Test
    void GIVEN_successful_deployment_result_WHEN_finishCurrentDeployment_THEN_clean_group_data()
            throws InterruptedException, InvalidRequestException, JsonProcessingException, ServiceLoadException {
        updateDeploymentObject(currentDeploymentFinisher);

        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);

        String expectedGroupName = GROUP_NAME;
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, expectedGroupName, allGroupTopics);

        Topics groupToLastDeploymentTopics = Topics.of(context, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null);
        Topics lastDeploymentGroupTopics = Topics.of(context, expectedGroupName, groupToLastDeploymentTopics);
        groupToLastDeploymentTopics.children.put(new CaseInsensitiveString(expectedGroupName), lastDeploymentGroupTopics);

        Topics groupMembershipTopics = Topics.of(context, GROUP_MEMBERSHIP_TOPICS, null);
        Topic pkgTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        Topic groupTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12");
        Topic groupNameTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME,
                expectedGroupName);
        Map<CaseInsensitiveString, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                pkgTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                groupTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                groupNameTopic1);
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.put(new CaseInsensitiveString(expectedGroupName), deploymentGroupTopics);


        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookupTopics(GROUP_TO_LAST_DEPLOYMENT_TOPICS)).thenReturn(groupToLastDeploymentTopics);
        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(groupMembershipTopics);

        currentDeploymentFinisher.finishCurrentDeployment(result, false);

        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.SUCCEEDED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));

    }

    @Test
    void GIVEN_rejected_deployment_result_WHEN_finishCurrentDeployment_THEN_deployment_completes_with_rejection(
            ExtensionContext extContext)
            throws InterruptedException, InvalidRequestException, JsonProcessingException {

        updateDeploymentObject(currentDeploymentFinisher);
        ignoreExceptionUltimateCauseOfType(extContext, Exception.class);
        DeploymentResult result = new DeploymentResult(
                DeploymentResult.DeploymentStatus.REJECTED,
                new Exception("Deployment Rejected"));

        currentDeploymentFinisher.finishCurrentDeployment(result, false);

        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.REJECTED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));

    }

    @Test
    void GIVEN_failed_deployment_result_WHEN_finishCurrentDeployment_THEN_deployment_completes_with_failure(
            ExtensionContext extContext)
            throws InterruptedException, InvalidRequestException, JsonProcessingException, ServiceLoadException {
        updateDeploymentObject(currentDeploymentFinisher);

        ignoreExceptionUltimateCauseOfType(extContext, Exception.class);
        DeploymentResult result = new DeploymentResult(
                DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED,
                new Exception("Deployment Failed"));

        String expectedGroupName = GROUP_NAME;
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, expectedGroupName, allGroupTopics);

        Topics groupToLastDeploymentTopics = Topics.of(context, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null);
        Topics lastDeploymentGroupTopics = Topics.of(context, expectedGroupName, groupToLastDeploymentTopics);
        groupToLastDeploymentTopics.children.put(new CaseInsensitiveString(expectedGroupName), lastDeploymentGroupTopics);

        Topics groupMembershipTopics = Topics.of(context, GROUP_MEMBERSHIP_TOPICS, null);
        groupMembershipTopics.lookup(expectedGroupName);
        Topic pkgTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        Topic groupTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12");
        Topic groupNameTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME,
                expectedGroupName);
        Map<CaseInsensitiveString, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                pkgTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                groupTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                groupNameTopic1);
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.put(new CaseInsensitiveString(expectedGroupName), deploymentGroupTopics);

        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookupTopics(GROUP_TO_LAST_DEPLOYMENT_TOPICS)).thenReturn(groupToLastDeploymentTopics);
        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(groupMembershipTopics);


        when(kernel.locate(any())).thenReturn(mockGreengrassService);
        when(mockGreengrassService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);

        currentDeploymentFinisher.finishCurrentDeployment(result, false);

        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }

    @Test
    void GIVEN_deployment_failed_with_exception_WHEN_updateStatusDetailsFromException_THEN_return_failure_status() {
        Deployment.DeploymentType deploymentType = Deployment.DeploymentType.IOT_JOBS;
        Throwable failureCause = new DeploymentTaskFailureException("Deployment Failed");

        Map<String, Object> expectedStatusDetails = new HashMap<>();
        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(failureCause, deploymentType);
        expectedStatusDetails.put(DEPLOYMENT_ERROR_STACK_KEY, errorReport.getLeft());
        expectedStatusDetails.put(DEPLOYMENT_ERROR_TYPES_KEY, errorReport.getRight());
        expectedStatusDetails.put(DEPLOYMENT_FAILURE_CAUSE_KEY, Utils.generateFailureMessage(failureCause));

        Map<String, Object> actualStatusDetails = currentDeploymentFinisher.updateStatusDetailsFromException(
                new HashMap<>(),
                failureCause,
                deploymentType);

        assertNotNull(actualStatusDetails);
        assertEquals(expectedStatusDetails, actualStatusDetails, "Failed to update Status Details correctly");
    }

    @Test
    void GIVEN_groupsToRootComponents_WHEN_setComponentsToGroupsMapping_THEN_get_correct_componentsToGroupsTopics()
            throws Exception {
        // GIVEN
        //   GroupToRootComponents:
        //      LOCAL_DEPLOYMENT:
        //        component1:
        //          groupConfigArn: "asdf"
        //          groupConfigName: "LOCAL_DEPLOYMENT"
        //          version: "1.0.0"
        //        AnotherRootComponent:
        //          groupConfigArn: "asdf"
        //          groupConfigName: "LOCAL_DEPLOYMENT"
        //          version: "2.0.0"
        //      thinggroup/group1:
        //        component1:
        //          groupConfigArn: "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1"
        //          groupConfigName: "thinggroup/group1"
        //          version: "1.0.0"
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, GROUP_NAME, allGroupTopics);
        Topics deploymentGroupTopics2 = Topics.of(context, LOCAL_DEPLOYMENT_GROUP_NAME, allGroupTopics);

        // Set up 1st deployment for EXPECTED_GROUP_NAME
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, TEST_CONFIGURATION_ARN));
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, GROUP_NAME));

        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString(GROUP_NAME), deploymentGroupTopics);

        // Set up 2nd deployment for LOCAL_DEPLOYMENT_GROUP_NAME
        Topics pkgTopics2 = Topics.of(context, "AnotherRootComponent", deploymentGroupTopics2);
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "2.0.0"));
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, "asdf"));
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, LOCAL_DEPLOYMENT_GROUP_NAME));
        deploymentGroupTopics2.children.put(new CaseInsensitiveString("AnotherRootComponent"), pkgTopics2);

        Topics pkgTopics3 = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics2);
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, "asdf"));
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, LOCAL_DEPLOYMENT_GROUP_NAME));
        deploymentGroupTopics2.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics3);

        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString(LOCAL_DEPLOYMENT_GROUP_NAME),
                deploymentGroupTopics2);

        // Set up mocks
        Topics componentsToGroupsTopics = mock(Topics.class);
        doReturn(componentsToGroupsTopics).when(config).lookupTopics(eq(COMPONENTS_TO_GROUPS_TOPICS));
        GreengrassService expectedRootService = mock(GreengrassService.class);
        GreengrassService anotherRootService = mock(GreengrassService.class);
        GreengrassService dependencyService = mock(GreengrassService.class);
        doReturn(expectedRootService).when(kernel).locate(eq(EXPECTED_ROOT_PACKAGE_NAME));
        doReturn(anotherRootService).when(kernel).locate(eq("AnotherRootComponent"));
        doReturn(dependencyService).when(kernel).locate(eq("Dependency"));
        doReturn(new HashMap<GreengrassService, DependencyType>() {{ put(dependencyService, DependencyType.HARD);}})
                .when(expectedRootService).getDependencies();
        doReturn(new HashMap<GreengrassService, DependencyType>() {{ put(dependencyService, DependencyType.HARD);}})
                .when(anotherRootService).getDependencies();
        doReturn(emptyMap()).when(dependencyService).getDependencies();
        doReturn(EXPECTED_ROOT_PACKAGE_NAME).when(expectedRootService).getName();
        doReturn("AnotherRootComponent").when(anotherRootService).getName();
        doReturn("Dependency").when(dependencyService).getName();

        // WHEN
        currentDeploymentFinisher.setComponentsToGroupsMapping(allGroupTopics);

        // THEN
        //   ComponentToGroups:
        //      component1:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //        "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1": "thinggroup/group1"
        //      AnotherRootComponent:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //      Dependency:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //        "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1": "thinggroup/group1"
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(componentsToGroupsTopics).replaceAndWait(mapCaptor.capture());
        Map<String, Object> groupToRootPackages = mapCaptor.getValue();

        assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));
        Map<String, String> expectedRootComponentMap =
                (Map<String, String>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME);
        assertEquals(2, expectedRootComponentMap.size());
        assertThat(expectedRootComponentMap, hasEntry(TEST_CONFIGURATION_ARN, GROUP_NAME));
        assertThat(expectedRootComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));

        assertThat(groupToRootPackages, hasKey("AnotherRootComponent"));
        Map<String, String> anotherRootComponentMap = (Map<String, String>) groupToRootPackages.get(
                "AnotherRootComponent");
        assertEquals(1, anotherRootComponentMap.size());
        assertThat(anotherRootComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));

        assertThat(groupToRootPackages, hasKey("Dependency"));
        Map<String, String> expectedDepComponentMap =
                (Map<String, String>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME);
        assertEquals(2, expectedDepComponentMap.size());
        assertThat(expectedDepComponentMap, hasEntry(TEST_CONFIGURATION_ARN, GROUP_NAME));
        assertThat(expectedDepComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));
    }

    String getTestDeploymentDocument() {
        return new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n")).replace(CONFIG_ARN_PLACEHOLDER, TEST_CONFIGURATION_ARN);
    }

    private void updateDeploymentObject(CurrentDeploymentFinisher currentDeploymentFinisher) throws JsonProcessingException, InvalidRequestException {
        String jobDocumentString = deployment.getDeploymentDocument();
        Configuration configuration = SerializerFactory.getFailSafeJsonObjectMapper()
                .readValue(jobDocumentString, Configuration.class);
        DeploymentDocument document = DeploymentDocumentConverter.convertFromDeploymentConfiguration(configuration);
        deployment.setDeploymentDocumentObj(document);
        currentDeploymentFinisher.setDeployment(deployment);
    }
}
