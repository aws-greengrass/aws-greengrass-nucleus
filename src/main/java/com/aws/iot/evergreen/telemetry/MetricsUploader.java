package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class MetricsUploader {
    public static final Logger logger = LogManager.getLogger(MetricsUploader.class);

    /**
     * Upload metrics on based on upload frequency.
     * @param context use this to schedule thread pool.
     */
    public void uploadMetrics(Context context) {
        // TODO read from a telemetry config file.
        for (Map.Entry<String, TelemetryDataConfig> config : MetricsAgent.createSampleConfiguration().entrySet()) {
            TelemetryDataConfig metricConfig = config.getValue();
            ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
            executor.scheduleAtFixedRate(readLogsAndUpload(metricConfig),0, metricConfig.getUploadFrequency(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private Runnable readLogsAndUpload(TelemetryDataConfig config) {
        return () -> {
            logger.atTrace().log(config);
            // read aggregated logs from file and upload
        };
    }
}
