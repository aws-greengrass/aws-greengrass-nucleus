# JMH Benchmarks for Evergreen
This sub-project contains JMH benchmarks for the Evergreen project.

These benchmarks measure important metrics including execution time and memory usage.

We run the `ForcedGcMemoryProfiler` to capture memory usage statistics at key points during the benchmark run.
Profiles are captured using `ForcedGcMemoryProfiler.recordUsedMemory()`. All the data points captured will
be averaged together.

The `pom.xml` file in this project is setup by JMH using the shade plugin to generate a jar file with no
external dependencies. This jar can be run simply using a command like `java -jar target/benchmarks.jar -wi 0 -f 1 -i 1 -prof com.aws.iot.evergreen.jmh.profilers.ForcedGcMemoryProfiler -jvmArgs "-XX:NativeMemoryTracking=summary"`

For additional information about the command line options provided by JMH, just run `java -jar target/benchmarks.jar
 -h`.
