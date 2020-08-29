package com.aws.iot.evergreen.integrationtests.e2e.logsuploader;

import com.amazonaws.AbortedException;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.logging.impl.config.LogStore;
import com.aws.iot.evergreen.logsuploader.model.ComponentType;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.event.Level;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.logsuploader.CloudWatchLogsMerger.DEFAULT_LOG_GROUP_NAME;
import static com.aws.iot.evergreen.logsuploader.CloudWatchLogsMerger.DEFAULT_LOG_STREAM_NAME;
import static com.aws.iot.evergreen.logsuploader.LogsUploaderService.LOGS_UPLOADER_SERVICE_TOPICS;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessageSubstring;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@Tag("E2E")
public class LogsUploaderServiceTest extends BaseE2ETestCase {
    private final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH);
    private String logGroupName;
    private String logStreamName;

    @BeforeAll
    static void setup() {
        LogManager.getConfig().setStoreType(LogStore.FILE);
        LogManager.getConfig().setLevel(Level.DEBUG);
    }

    @AfterAll
    static void cleanUpAfter() {
        final File folder = new File(LogManager.getLoggerDirectoryPath().toString());
        System.out.println(LogManager.getLoggerDirectoryPath());
        final File[] files = folder.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.getName().startsWith("evergreen.log") && !file.delete()) {
                    System.err.println("Can't remove " + file.getAbsolutePath());
                }
            }
        }
    }

    @AfterEach
    void afterEach() {
        /*try {
            cloudWatchLogsClient.deleteLogStream(DeleteLogStreamRequest.builder()
                    .logGroupName(logGroupName)
                    .logStreamName(logStreamName)
                    .build());
            cloudWatchLogsClient.deleteLogGroup(DeleteLogGroupRequest.builder()
                    .logGroupName(logGroupName)
                    .build());
        } catch (CloudWatchLogsException e) {
            logger.atError().cause(e).log();
        }*/

        try {
            if (kernel != null) {
                kernel.shutdown();
            }
        } finally {
            // Cleanup all IoT thing resources we created
            cleanup();
        }
    }

    @BeforeEach
    void launchKernel() throws Exception {
        kernel = new Kernel().parseArgs("-i", LogsUploaderServiceTest.class.getResource("logsuploaderexample.yaml").toString());
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, GAMMA_REGION.toString());
        setupTesRoleAndAlias();
        CountDownLatch logsuploaderRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(LOGS_UPLOADER_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                logsuploaderRunning.countDown();
            }
        });
        kernel.launch();

        // TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        TimeUnit.SECONDS.sleep(10);
        assertTrue(logsuploaderRunning.await(5, TimeUnit.SECONDS));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    public void GIVEN_system_log_files_WHEN_upload_THEN_all_logs_are_uploaded_to_cloud_watch(ExtensionContext context)
            throws InterruptedException {
        logGroupName = DEFAULT_LOG_GROUP_NAME
                .replace("{componentType}", ComponentType.GreenGrassSystemComponent.toString())
                .replace("{region}", GAMMA_REGION.toString())
                .replace("{name-of-component}", "System");
        logStreamName = DEFAULT_LOG_STREAM_NAME
                .replace("{name-of-core}", thingInfo.getThingName())
                .replace("{date}", DATE_FORMATTER.format(new Date()));

        ignoreExceptionOfType(context, ResourceAlreadyExistsException.class);
        ignoreExceptionOfType(context, AbortedException.class);
        ignoreExceptionUltimateCauseOfType(context, ResourceAlreadyExistsException.class);
        ignoreExceptionUltimateCauseOfType(context, AbortedException.class);
        ignoreExceptionWithMessageSubstring(context, "The specified log group already exists");
        ignoreExceptionWithMessageSubstring(context, "The specified log stream already exists");
        TimeUnit.SECONDS.sleep(90);


        DescribeLogGroupsRequest describeLogGroupsRequest = DescribeLogGroupsRequest.builder()
                .logGroupNamePrefix(logGroupName)
                .build();
        DescribeLogGroupsResponse describeLogGroupsResponse =
                cloudWatchLogsClient.describeLogGroups(describeLogGroupsRequest);
        assertNotNull(describeLogGroupsResponse);
        assertTrue(describeLogGroupsResponse.hasLogGroups());
        assertTrue(describeLogGroupsResponse.logGroups().stream().anyMatch(logGroup -> logGroup.logGroupName().equals(logGroupName)));
        DescribeLogStreamsRequest describeLogStreamsRequest = DescribeLogStreamsRequest.builder()
                .logGroupName(logGroupName)
                .logStreamNamePrefix(logStreamName)
                .build();
        DescribeLogStreamsResponse describeLogStreamsResponse =
                cloudWatchLogsClient.describeLogStreams(describeLogStreamsRequest);
        assertNotNull(describeLogStreamsResponse);
        assertTrue(describeLogStreamsResponse.hasLogStreams());
        assertTrue(describeLogStreamsResponse.logStreams().stream().anyMatch(logStream -> logStream.logStreamName().equals(logStreamName)));
    }
}
