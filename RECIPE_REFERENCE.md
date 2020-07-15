# Component Recipe Reference
## Reference and guidelines
This reference describes version 1 of component recipe file format.

Component recipe is a single yaml/json file for component author to define component deployment and runtime
 characteristics in AWS greengrass ecosystem.
## Recipe file structure and examples
Here is a sample recipe file in yaml format which defines a simple HelloWorld application can run on AWS greengrass
 managed devices.
```yaml
---
RecipeTemplateVersion: '2020-01-25'
ComponentName: HelloWorld
Version: '1.0.0'
Description: Hello World App for Evergreen
Publisher: Amazon
Platforms:
  - debian
Parameters:
  - name: Message
    value: 'World'
    type: STRING
Lifecycle:
  run:
    debian: python3 {{artifacts:path}}/hello_world.py '{{params:Message.value}}'
      done
Artifacts:
  debian:
    - "greengrass:hello_world.py"
Dependencies:
  debian:
    shared.python:
      VersionRequirement: ~3.6
      DependencyType: HARD
```
The topics on this reference are organized by top-level keys grouped by functions, such as providing metadata, or
 defining deployment and/or runtime behaviors. Top-level keys can have options that support them as sub-topics. This
  maps to the `<key>: <options>: <value>` indent structure of recipe file.
## Component metadata
Keys in this group provides component metadata, which are usually used for processing components in greengrass
 environment. The metadata is often used for indexing and filtering as well.
### RECIPE TEMPLATE VERSION
Define the version of recipe itself
```yaml
RecipeTemplateVersion: '2020-01-25'
```
### COMPONENT NAME
Component name identifier, reverse DNS notation is recommended. Component name is unique private component registry
. Private component which has same name occludes public available component.
> note: component name is also used as service name, since component to service is 1:1 mapping.
```yaml
ComponentName: com.aws.greengrass.HelloWorld
```
### VERSION
Component verison, use [semantic versioning](https://semver.org/) standard
```yaml
Version: 1.6.1
```
### DESCRIPTION
Text description of component
```yaml
Description: Hello World App for Evergreen
```
### PUBLISHER
Publisher of component
```yaml
Publisher: Amazon
```
### Platforms
A list of platforms component declaring support. Greengrass will apply the constrains before provisioning component
 on device.
 > note: the platform constraints only support OS with text match now, no CPU architecture constraints support yet.
```yaml
Platforms:
  - debian
  - android
```
## Service configuration
Keys in this group are mostly used for defining component runtime characteristics.
### LIFECYCLE
Specify lifecycle management scripts for component represented service
```yaml
Lifecycle:
  environment: # apply to all commands to the service.
    <key>: <defaultValue>
        
  install:
    skipif: onpath <executable>|exists <file>
    script:
    timeout: # default to be 120 seconds.
    environment: # optional
      <key>: <overrideValue>
    
  startup: # mutually exclusive from 'run'
    script: # eg: brew services start influxdb
    timeout: # optional
    environment:  # optional, override
      
  run: # mutually exclusive from 'startup'
    script:
    environment: # optional, override
    timeout: # optional
    periodicity: # perodically run the command
    
  shutdown: # can co-exist with both startup/run
    script:
    environment: # optional, override
    timeout: # optional
  
  healthcheck: # do health check when service is in Running
    script: # non-zero exit trigger error
    recheckPeriod: # optional, default to be 0
    environment: # override
    
  recover:
    script: # will be run every time service enters error.
    environment: # optional, override
    # referring to https://docs.docker.com/v17.12/compose/compose-file/#restart_policy
    retryPolicy:
      delay: # default to be 0. Time to wait between retry.
      maxAttempts: # default to be infinite. After N times of error, service enter Broken state.
      window: # how long to wait before deciding if a restart has succeeded
  
  checkIfSafeToUpdate:
    recheckPeriod: 5
    script: 
    
  updatesCompleted:
    script:
```
### PARAMETERS
Component author specifies configuration parameters used in lifecycle management scripts, and/or accessible by
 service runtime, which can both read and write configuration values.
```yaml
Parameters:
  - name: Message
    value: 'World'
    type: STRING
```
#### name
name of parameter
#### value
default value of parameter
#### type
type of parameter. Current supported types includes:
* String
* Number
* Boolean
## Dependencies
Keys in the group are used for describing component deployment dependencies. The dependencies could be
 component necessary artifacts or the other components.
### ARTIFACTS
A list of artifacts component relies on as resources, such as binary, scripts, images etc. The section supports platform
 hierarchy.
```yaml
Artifacts:
  debian
    - greengrass:hello_world.py
    - s3://example-bucket/path/to/object
```
Artifacts are referenced by artifact URIs. Currently greengrass supports greengrass repository and s3 as artifact
 storage location.
### DEPENDENCIES
Describe component dependencies, the versions of dependencies will be resolved during deployment.
> note: Services represented by components will be started/stopped with respect to dependency order.
```yaml
Dependencies:
  debian:
    shared.python:
      VersionRequirement: ~3.6
      DependencyType: HARD
```
#### Version Requirement
Specify dependency version requirements, the requirements support NPM-style syntax.
#### Dependency Type

