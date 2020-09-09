package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.impl.config.TelemetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.telemetry.MetricsAggregator.AGGREGATE_METRICS_FILE;

public class MetricsUploader {
    public static final Logger logger = LogManager.getLogger(MetricsUploader.class);
    private static final int MILLI_SECONDS = 1000;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * This function returns the set of all the aggregated metric data points that are to be published to the cloud
     * since the last upload.
     *
     * @param uploadIntervalSec periodic interval in seconds for the publishing the metrics
     * @param currTimestamp timestamp at which the publish is initiated
     */
    public Map<Long,List<MetricsAggregator.AggregatedMetric>>
    getAggregatedMetrics(int uploadIntervalSec, long currTimestamp) {
        int uploadIntervalMilliSec = uploadIntervalSec * MILLI_SECONDS;
        Map<Long,List<MetricsAggregator.AggregatedMetric>> aggUploadMetrics = new HashMap<>();
        MetricsAggregator.AggregatedMetric am;
        try {
            List<Path> paths = Files
                    .walk(TelemetryConfig.getTelemetryDirectory())
                    .filter(Files::isRegularFile)
                    .filter((path) -> {
                        Object fileName = null;
                        if (path != null) {
                            fileName = path.getFileName();
                        }
                        if (fileName == null) {
                            fileName = "";
                        }
                        return fileName.toString().startsWith(AGGREGATE_METRICS_FILE);
                    }).collect(Collectors.toList());
            for (Path path :paths) {
                /*
                 Read AggregatedMetrics file from the file at Telemetry.
                 Read only modified files and publish only new values based on the timestamp.
                 */
                if (currTimestamp - new File(path.toString()).lastModified() <= uploadIntervalMilliSec) {
                    List<String> logs = Files.lines(Paths.get(path.toString())).collect(Collectors.toList());
                    for (String log : logs) {
                        try {
                            /*

                            {"thread":"main","level":"TRACE","eventType":null,

                            "message":"{\"TS\":1599617227533,\"NS\":\"SystemMetrics\",\"M\":[{\"N\":\"CpuUsage\",

                            \"V\":60.0,\"U\":\"Percent\"},{\"N\":\"TotalNumberOfFDs\",\"V\":6000.0,\"U\":\"Count\"},

                            {\"N\":\"SystemMemUsage\",\"V\":3000.0,\"U\":\"Megabytes\"}]}","contexts":{},"loggerName":

                            "Metrics-AggregateMetrics","timestamp":1599617227595,"cause":null}

                             */
                            am = objectMapper.readValue(objectMapper.readTree(log).get("message").asText(),
                                    MetricsAggregator.AggregatedMetric.class);
                            if (am != null && currTimestamp - am.getTimestamp() <= uploadIntervalMilliSec) {
                                aggUploadMetrics.computeIfAbsent(currTimestamp, k -> new ArrayList<>()).add(am);
                            }
                        } catch (MismatchedInputException e) {
                            logger.atError().log("Unable to parse the aggregated metric log: ", e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.atError().log("Unable to parse the aggregated metric log file: ", e);
        }
        aggUploadMetrics.putIfAbsent(currTimestamp, Collections.EMPTY_LIST);
        return aggUploadMetrics;
    }
}
