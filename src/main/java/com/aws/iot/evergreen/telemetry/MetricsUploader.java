package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
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

    /**
     * This function returns the set of all the aggregated metric data points that are to be published to the cloud
     * since the last upload.
     *
     * @param uploadIntervalSec periodic interval in seconds for the publishing the metrics
     * @param currentTimestamp timestamp at which the publish is initiated
     */
    public Map<Long,List<MetricsAggregator.AggregatedMetric>>
    getAggregatedMetrics(int uploadIntervalSec, long currentTimestamp) {
        int uploadIntervalMilliSec = uploadIntervalSec * MILLI_SECONDS;
        Map<Long,List<MetricsAggregator.AggregatedMetric>> aggUploadMetrics = new HashMap<>();
        MetricsAggregator.AggregatedMetric am;
        try {
            List<Path> paths = Files
                    .walk(MetricFactory.getTelemetryDirectory())
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
                if (currentTimestamp - new File(path.toString()).lastModified() <= uploadIntervalMilliSec) {
                    List<String> logs = Files.lines(Paths.get(path.toString())).collect(Collectors.toList());
                    for (String log : logs) {
                        try {
                            /*
                            [0]  [1] [2]        [3]          [4]         [5]             [6]
                            2020 Aug 28 12:08:21,520-0700 [TRACE] (pool-3-thread-4) Metrics-KernelComponents:

                            [7]
                            {"TS":1599256194930,"NS":"SystemMetrics","M":[{
                            "N":"TotalNumberOfFDs","V":5000.0,"U":"Count"},
                            {"N":"CpuUsage","V":20.0,"U":"Percent"},
                            {"N":"SystemMemUsage","V":3000.0,"U":"Megabytes"}]}. {}

                             */
                            am = new ObjectMapper()
                                    .readValue(log.split(" ")[7], MetricsAggregator.AggregatedMetric.class);
                            if (am != null && currentTimestamp - am.getTimestamp() <= uploadIntervalMilliSec) {
                                aggUploadMetrics.computeIfAbsent(currentTimestamp, k -> new ArrayList<>()).add(am);
                            }
                        } catch (MismatchedInputException mis) {
                            logger.atError().log("Unable to parse the aggregated metric log: " + mis);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.atError().log(e);
        }
        aggUploadMetrics.putIfAbsent(currentTimestamp, Collections.EMPTY_LIST);
        return aggUploadMetrics;
    }
}
