# LogsUploaderService

# Startup
1. [***LogsUploaderService***](/src/main/java/com/aws/iot/evergreen/logsuploader/LogsUploaderService.java) starts as an
evergreen service, which is by default disabled.

# Shutdown
Service lifecycle is managed by kernel and as part of kernel shutdown.

# Workflow


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