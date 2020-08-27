# LogsUploaderService
It is responsible for uploading logs from the device from greengrass as well as non-greengrass components to CloudWatch.
Since the customers can use either the Evergreen Logging and Metrics service framework or any other framework to log, the 
logs uploader needs to be smart in order to handle these different formats of logs. 
The logs uploader should be able to handle any network disruptions or device reboots. The logs uploader should smartly
manage the log rotation for different logging frameworks and upload the logs on a “best effort” basis.
 
The customers can add each components configuration for where the log files are location and how they are rotated.
# Startup
1. [***LogsUploaderService***](/src/main/java/com/aws/iot/evergreen/logsuploader/LogsUploaderService.java) starts as an
evergreen service, which is by default disabled.

# Shutdown
Service lifecycle is managed by kernel and as part of kernel shutdown.

# Workflow
1. The customers add configuration for the component to specify log files directory and log files regex.
2. The customers can also add configuration to upload all system components to cloudwatch without having to specify where they are located.
3. The customers should handle the writing log files and handle log rotations.
4. The customers can configure the logs uplaoder to delete the log files after all the logs from the log file have been uploaded.


# Sample Configuration
```
services:
  main:
    lifecycle:
  LogsUploaderService:
    parameters:
      componentsLogsConfiguration: "{\"ComponentLogInformation\": {\"ComponentNameA\": {\"LogFileRegex\": \"^log.txt\w*”,\"LogFileDirectoryPath\": \"/var/usr/\", \"MultiLineStartPattern\": \"\{'timestamp”,\"MinimumLogLevel\": \"INFO\",\"DiskSpaceLimit\": \"10\",\"DiskSpaceLimitUnit\": \"GB\",\"DeleteLogFileAfterCloudUpload\": true},\"ComponentNameB\": {\"LogFileRegex\": \"^evergreen.log\w*”,\"LogFileDirectoryPath\": \"/tmp/.evergreen/\"},},\"SystemLogsConfiguration\": {\"UploadToCloudWatch\": true,\"MinimumLogLevel\": \"INFO\",\"DiskSpaceLimit\": \"25\",\"DiskSpaceLimitUnit\": \"MB\"}}"
    lifecycle:
```