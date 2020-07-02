# Evergreen Kernel Configuration Schema
Detailed doc in https://quip-amazon.com/35xMAtuSgvha

## Resolve config workflow

**Config load/merge**

Raw yaml file → Resolve platform & Platform Validation (eg: return error on unrecognized platform) → Resolve Config & Validation → take effect

Once the config is resolved and loaded in memory, it doesn’t have any platform branches.

## Config Schema - Overview

Config file defines how kernel starts each service.
A config file in kernel memory after resolving platform looks like below:

```
version:
  <version> # schema version.
services:
  <service1>: #Service name uniquely identifies a service
    version: # service version. In the format of x.y.z
    dependencies: # declare dependency, 
      - <serviceName>:[SOFT/HARD]
        
    lifecycle: # lifecycle commands.
    
    resources: # service reserved resources path.

    logging: # logging config.
      
    parameters: # custom config.
    
    runtime: # namespace for service local datastore
        # not rolled back during deployment

  <service2>:
    lifecycle:
    logging:
    
  _AUTH_TOKENS: # auth token read by AuthHandler
    <authToken>: <serviceName>

system:
  <kernel system config> 

registered-resource: # resources registered by service
  <path>: <SDAResource>

setenv:
  # global env var for IPC, TES
```

### Config Validation

Root keys have to be recognized keys.

## Service Config Keys

### Lifecycle

‘Services’ field contains all services config.

```
services: 
<serviceName>:
  lifecycle:
    install:
      skipif: onpath <executable>|exists <file>
      script:
      timeout: # optional. timeout in number of seconds. Default to 120 sec.
      setenv: # key-value environment variables. optional, can override the parent 'setenv'
      
    startup: # mutually exclusive from 'run'
      script:
      timeout: # Default to 120 sec
      setenv:

    run: # mutually exclusive from 'startup'
      script:
      setenv:
      periodicity: # Perodically run the command
      
    shutdown: # can co-exist with both startup/run
      script:
      setenv:
      timeout: # Optional. Default to 15 seconds.
    
    setenv: # apply to all commands
      <key>: defaultValue

    recover:
      script: # will be run every time service enters error.
      setenv:

    checkIfSafeToUpdate:
       recheckPeriod: # default 30 seconds.
       timeout: # default 5 sec.
       script:

    updatesCompleted:
       script:
       timeout: # default 5 sec.
```

### Dependency

Detailed documentation is at [Evergreen Service Hot-pluggable Dependencies](https://quip-amazon.com/y29dAC02fUBu)

```
myCustomService:
  dependencies:
    - <serviceName>:dependencyType
```

**DependencyType**
DependencyType is either **SOFT or HARD**

### Resource
Service can reserve topic for resources:
```
resources:
      - evergreen_1._mqtt._tcp.local
      - evergreen_1._http._tcp.local
```

### Logging

Detailed design doc at: [[Design] Evergreen Logging Service](https://quip-amazon.com/QbwaANkaR95C)

### Custom configuration

The reason of using custom configuration instead of environment variable is to support dynamically reload config change without restarting the service. Service can listen on config change through IPC and apply the new change without restart.

Any custom configuration locates in ‘custom’ field. Custom config field are passed to service through IPC. Currently custom configuration only support one level key-value. Detailed discussion is at [Custom config supporting complex data structure](https://quip-amazon.com/35xMAtuSgvha#aeM9CAdxOuX)

All changes in custom fields will not restart service. Details of how configuration change push/listening can be supported is detailed in [Configuration IPC Application](https://quip-amazon.com/xtNNAdaAl9ZA).

Detail of dynamic load config is at [Dynamically reload config without restarting Evergreen service](https://quip-amazon.com/mld0ATVx17YK)

```
myCustomService: 
  lifecycle:
  dependencies:
  custom: 
    key1: val1
    config2: val2
```

## System Config Components

```
system: 
  thingName:
  certificateFilePath:
  privateKeyPath:
  rootCaPath:
  iotDataEndpoint:
  iotCredEndpoint:
  awsRegion: "us-west-2"
```


