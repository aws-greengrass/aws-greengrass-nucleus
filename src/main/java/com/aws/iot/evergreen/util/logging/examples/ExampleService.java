package com.aws.iot.evergreen.util.logging.examples;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.logging.api.LogManager;
import com.aws.iot.evergreen.util.logging.api.Logger;
import com.aws.iot.evergreen.util.logging.api.MetricsFactory;

import javax.inject.Inject;
import javax.measure.unit.Unit;

import static com.aws.iot.evergreen.dependency.State.*;

public class ExampleService extends EvergreenService {
    @Inject LogManager logManager;
    Logger logger;
    MetricsFactory metricsFactory;

    public class Request {
        public String clientID;
        public String requestID;
    }

    public ExampleService(Topics c) {
        super(c);

        logger = logManager.getLogger(this.getClass());
        logger.addDefaultKeyValue("service", "ExampleServiceName");
        logger.atInfo().addKeyValue("version", "1.0.0-dev").setEventType("service_load").log();

        metricsFactory = logManager.getMetricsFactory(this.getClass());
        metricsFactory.addDefaultDimension("deviceID", "foo");
        metricsFactory.addDefaultDimension("fleetID", "bar");
    }

    public void someMethod() {
        // Service startup
        logger.atDebug().addKeyValue("fromState", AwaitingStartup).addKeyValue("toState", Running).setEventType("service_state_change_success").log();

        // Service taking requests
        Request req = new Request();
        logger.atInfo().addKeyValue("request", req).setEventType("service_recv_req").log();
        metricsFactory.newMetrics().setNamespace("ExampleService").addDimension("clientID", req.clientID).addMetric("requestCount", 1, Unit.ONE).flush();

        // Service running into errors
        Throwable someError = new Exception("some error");
        logger.atWarn().setCause(someError).setEventType("service_error").log();
        metricsFactory.newMetrics().setNamespace("ExampleService").addMetric("errorCount", 1, Unit.ONE).flush();

        if (logger.isTraceEnabled()) {
            // some expensive object construction
            logger.atTrace().log(someError.getStackTrace());
        }
    }

}

/** Output
 *
 * exampleService.log
 *
 {"V": "1.0", "TS": "1576786259025", "LN": "com.aws.iot.evergreen.util.logging.examples.ExampleService", "LVL": "info", "T": "service_load", "D": {"service": "ExampleServiceName", "version": "1.0.0-dev"}}
 {"V": "1.0", "TS": "1576786259025", "LN": "com.aws.iot.evergreen.util.logging.examples.ExampleService", "LVL": "debug", "T": "service_state_change_success", "D": {"service": "ExampleServiceName", "fromState": "AwaitingStartup", "toState": "Running"}}
 {"V": "1.0", "TS": "1576786259025", "LN": "com.aws.iot.evergreen.util.logging.examples.ExampleService", "LVL": "info", "T": "service_recv_req", "D": {"service": "ExampleServiceName", "request": {"clientID": "baz", "requestID": "0000"}}}
 {"V": "1.0", "TS": "1576786259025", "LN": "com.aws.iot.evergreen.util.logging.examples.ExampleService", "LVL": "warn", "T": "service_error", "D": {"service": "ExampleServiceName", "EXC": "some error"}}
 {"V": "1.0", "TS": "1576786259025", "LN": "com.aws.iot.evergreen.util.logging.examples.ExampleService", "LVL": "trace", "MSG": "<stacktrace....>", "D": {"service": "ExampleServiceName"}}
 *
 * exampleService.metrics
 *
 {"TS": "1576786259025", "NS": "ExampleService", "M": [{"N": "requestCount", "V": 1, "U": "ONE"}], "D": {"deviceID": "foo", "fleetID": "bar", "clientID": "baz"}}
 {"TS": "1576786259025", "NS": "ExampleService", "M": [{"N": "errorCount", "V": 1, "U": "ONE"}], "D": {"deviceID": "foo", "fleetID": "bar"}}
 *
 */
