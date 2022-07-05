# Telemetry

Telemetry agent (TA) is a service that is responsible for publishing telemetry data from the device to the IoT cloud
 via an MQTT topic. TA periodically aggregates and publishes various types of metrics related to nucleus, system, mqtt
  and so on based on the telemetric configuration set by the customer.

## Workflow
There are three major steps involved in order for the TA to publish the telemetry data.
  - Creating a metric and emitting the data points
  - Aggregating the emitted metrics
  - Publishing the aggregated metrics

### Creating a metric and emitting the data points
Typically, metric creation and emission are component/service specific (excluding system/nucleus component state
 metrics).
##### Create a metric
Each metric has to be specified with its name, namespace it belongs to, aggregation type that we want to perform on it and unit of the metric. It is enough to create a metric just once.
```
    Metric metric = Metric.builder()
            .namespace("GreengrassComponents")
            .name("NumberOfComponentsInstalled")
            .unit(TelemetryUnit.Count)
            .aggregation(TelemetryAggregation.Average)
            .build();
```
##### Emit a metric
Emitting a metric data point is nothing but assiging a value to the metric and writing it to the file.
- The name of the file is always going to be the namespace of the metric and it always resides inside the `telemetry` directory.
```
    Telemetry
    |___ generic.log
    |___ GreengrassComponents.log
    |___ SystemMetrics.log
    |___ ...
```
- The file to which the metric has to be written is specified using the `MetricFactory`. If nothing is specified, then the metrics are written to "generic.log" file.
    ```
    MetricFactory metricFactory = new MetricFactory("GreengrassComponents");
    ```
- Emitting a metric can be done in two ways. 
1. Assign a value and timestamp to the metric before emitting it.
    ```
    metric.setValue(10);
    metric.setTimestamp(Instant.now().toEpochMilli());
    
    metricFactory.putMetricData(metric);
    ```
2. No need to assign the timestamp or value to the metric. Pass in the metric along with the value. Current timestamp will be assigned.
    ```
    metricFactory.putMetricData(metric,10);
    ```
Sample contents of the file `telemetryGreengrassComponents.log`
```
{
    "thread": "pool-1-thread-1",
    "level": "TRACE",
    "eventType": null,
    "message":"{\"NS\":\"GreengrassComponents\",\"N\":\"NumberOfComponentsInstalled\",\"U\":\"Count\",\"A\":\"Average\",\"Average":1,\"TS\":1600127551482}",
    "contexts": {},
    "loggerName": "Metrics-GreengrassComponents",
    "timestamp": 1599814408616,
    "cause": null
}
```
Message part of the log corresponds to the following structure
```
Metric 
|___ namespace
|___ name
|___ unit
|___ Aggregation
|___ value
|___ timestamp
```
### Aggregating the emitted metrics
Aggregation on the metric logs is performed based on the interval configured by the customer. By default, metrics are aggregated once in every one hour.

- Read the log files present in the Telemetry directory.
- Aggregate only those metrics that are emitted after the last aggregation and before the current time. This aggregation is metric specific.
- Example: The metric `NumberOfComponentsInstalled` has 100 occurrences in the `telemetryGreengrassComponents.log` file out of which 70 are emitted after the last aggregation. Based on the aggregation type of the metric specified, here `Average`, we need to perform average on all of these 70 values. So, we make a map with `NumberOfComponentsInstalled` as the key and the list of these 70 entries as the value and pass this list to a function where aggregation is performed(average,sum,max..)
- Once the metrics are aggregated for that interval, group them based on their namespace and write them to a file called `telemetryAggregateMetrics.log`.
```
{
    "thread": "pool-3-thread-3",
    "level": "TRACE",
    "eventType": null,
    "message": "{\"TS\":1599790572560,\"NS\":\"GreengrassComponents\",\"M\":[{\"N\":\"NumberOfComponentsStopping\",\"Average\":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsFinished\",\"Average\":1.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsRunning\",\"Average\":11.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsNew\",\"Average\":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsBroken\",\"Average":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsErrored\",\"Average":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsStarting\",\"Average":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsInstalled\",\"Average":0.0,\"U\":\"Count\"},{\"N\":\"NumberOfComponentsStateless\",\"Average":0.0,\"U\":\"Count\"}]}",
    "contexts": {},
    "loggerName": "Metrics-AggregateMetrics",
    "timestamp": 1599790572565,
    "cause": null
}
```
Message part of the log corresponds to the following structure.
```
Aggregated metric data point
|__ Timestamp
|__ Namespace(GreengrassComponents)
|___List
    |__ Metric 1 (NumberOfComponentsNew)
    |   |__ name
    |   |__ aggregated value
    |   |__ unit
    |__ Metric 2 (NumberOfComponentsBroken)
    |   |__ name
    |   |__ aggregated value
    |___|__ unit
```
Thus, in the given aggregation interval, we will write n logs to the AggregateMetrics.log file where n is the total number of metric namespaces available. Each of these n logs will contain the aggregation of metrics of that namespace(At present there are 4 namespaces).

### Publishing the aggregated metrics
Publishing the aggregated metrics is performed based on the interval configured by the customer. By default, metrics are published once in every day.
- Read `AggregateMetrics.log` present in the Telemetry directory.
- Publish only those metrics that are aggregated after the last publish and before the current time. This is essentially list of the above aggregated metrics.
- There will be mn entries in this list where n is the number of namespaces and m is the number of times the aggregation is performed. Ideally, there will be 24n entries as metrics are aggregated 24 times in a day before the publish.
- Only for `GreengrassComponents` namespace: There is an additional point for each namespace which is the accumulation of these aggregated points. So, there will be 24n + n points at the time of publishing data once a day.
  - The n metrics collected as the accumulation of the aggregated points have the same timestamp as publishing timestamp.