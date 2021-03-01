# Greengrass Nucleus Configuration Schema

## Configuration loading workflow

Raw yaml file → Resolve platform & Platform Validation (eg: return error on unrecognized platform) → Resolve Config
 & Validation → Take effect

Once the config is resolved and loaded in runtime, it doesn't have any platform-specific information.

## Config Schema - Overview

The config file defines how Nucleus starts each service. After loaded in runtime, the configuration looks like below:

```
system: 
  rootPath: "/greengrass/v2"

services:
  <service1>: # service name uniquely identifies a service
    version: # service version. In the format of x.y.z

    componentType: [GENERIC|PLUGIN|LAMBDA|NUCLEUS] # component type.

    dependencies: # dependency on other services
      - <serviceName>:[SOFT/HARD]
        
    runWith:
      posixUser: <username[:groupname]> # Optional. Posix user (and group) to run lifecycle steps as. Overrides runWithDefault in Nucleus config

    lifecycle: # lifecycle commands.

    configuration: # custom config.
      <configName>: <configValue>

      accessControl: # authorization to access Greengrass resources
        aws.greengrass.ipc.mqttproxy:
          policyId1:
            operations:
              - 'aws.greengrass#PublishToIoTCore'
              - "aws.greengrass#SubscribeToIoTCore"
            policyDescription: "access to IoT topics"
            resources:
              - "test/topic"
        aws.greengrass.ipc.pubsub:
          policyId1:
            operations:
              - 'aws.greengrass#PublishToTopic'
              - "aws.greengrass#SubscribeToTopic"
            policyDescription: "access to pubsub topics"
            resources:
              - "test/topic"
        aws.greengrass.SecretManager:
          policyId1:
            policyDescription: "access to secret"
            operations:
              - "aws.greengrass#GetSecretValue"
            resources:
              - "secret arn"

  <service2>:
    lifecycle:

setenv:
  # global environment variables for IPC, TES
```

### Config Validation

Root keys have to be recognized keys.

## Service Config
‘services’ field contains all services config.

### Lifecycle

```
<serviceName>:
  lifecycle:
    setenv: # This applies to all lifecycle steps
      <key>: <defaultValue>

    bootstrap:
      requiresPrivilege: true|false # Optional. Run with root privileges.
      script:
      setenv: # Optional. Key-value environment variables. It can override the parent 'setenv'.
      timeout: # Optional. Timeout in number of seconds. Default to 120 sec.

    install:
      requiresPrivilege: # Optional. Run with root privileges.
      script:
      setenv: # Optional. Key-value environment variables. It can override the parent 'setenv'.
      skipif: onpath <executable>|exists <file> # Optional.
      timeout: # Optional. Timeout in number of seconds. Default to 120 sec.
      
    startup: # This step is mutually exclusive from 'run'.
      requiresPrivilege: # Optional. Run with root privileges.
      script:
      setenv:
      skipif: onpath <executable>|exists <file> # Optional.
      timeout: # Optional. Timeout in number of seconds. Default to 120 sec.

    run: # This step is mutually exclusive from 'startup'.
      requiresPrivilege: # Optional. Run with root privileges.
      script:
      setenv:
      skipif: onpath <executable>|exists <file> # Optional.
      timeout: # Optional. Timeout in number of seconds. Default to no timeout.
      
    shutdown: # This step can co-exist with both startup and run
      requiresPrivilege: # Optional. Run with root privileges.
      script:
      setenv:
      skipif: onpath <executable>|exists <file> # Optional.
      timeout: # Optional. Timeout in number of seconds. Default to 15 seconds.
    
    recover: # This step runs every time service enters error state.
      requiresPrivilege: # Optional. Run with root privileges.
      script:
      setenv:
      skipif: onpath <executable>|exists <file> # Optional.
      timeout: # Optional. timeout in number of seconds. Default to 60 sec.
```

### Dependency

```
myCustomService:
  dependencies:
    - <serviceName>:<dependencyType>
```

DependencyType is either **SOFT or HARD**.
- SOFT – The dependent service doesn't restart if the dependency changes state.
- HARD – The dependent service restarts if the dependency changes state.

## System Config
System config that does not change after kernel setup is hidden from deployments
and modeled under the system config key
```
system: 
  rootPath: "/greengrass/v2"
  thingName: "test_thing"
  certificateFilePath: "root/thingCert.crt"
  privateKeyPath: "root/privKey.key"
  rootCaPath: "root/rootCA.pem"
```

System configuration that deployments are allowed to update is
modeled as component configuration for the Nucleus
```
services:
  main:
    lifecycle:
    dependencies:
      - TokenExchangeService
  aws.greengrass.Nucleus:
    configuration:
      awsRegion: "us-east-1"
      componentStoreMaxSizeBytes: 10000000000
      deploymentPollingFrequencySeconds: 15
      iotCredEndpoint: "xxxxxx.credentials.iot.us-east-1.amazonaws.com"
      iotDataEndpoint: "xxxxxx-ats.iot.us-east-1.amazonaws.com"
      iotRoleAlias: "tes_alias"
      fleetStatus:
        periodicStatusPublishIntervalSeconds: "86400"
      logging:
        level: INFO
        fileSizeKB: 1024
        totalLogsSizeKB: 10240
        format: TEXT
        outputDirectory: /path/to/logs/directory
        outputType: FILE
      mqtt:
        port: 8883
        keepAliveTimeoutMs: 60000
        pingTimeoutMs: 30000
        operationTimeoutMs: 30000
        maxInFlightPublishes: 5
        spooler:
          keepQos0WhenOffline: false
          maxSizeInBytes: 2621440
      networkProxy:
        noProxyAddresses: "example.com,a.b.c"
        proxy:
          url: "proxy_url"
          username: ""
          password: ""
      platformOverride:
        os: "customOs"
      runWithDefault:
        posixUser: "username[:groupname]"
      telemetry:
        enabled: true
        periodicAggregateMetricsIntervalSeconds: 3600
        periodicPublishMetricsIntervalSeconds: 86400
```