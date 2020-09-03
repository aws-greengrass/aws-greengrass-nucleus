# Component Recipe Reference
## Reference and guidelines
This reference describes version 2020-xx-xx of component recipe file format.

Component recipe is a single yaml/json file for the component author to define component deployment and runtime
 characteristics in the AWS Greengrass ecosystem.
## Recipe file structure and examples
Here is a sample recipe file in yaml format which defines a simple HelloWorld application can run on AWS Greengrass
 managed devices. It defines a specific manifest for x86_64 windows as well as default manifest for the other platforms.
```yaml
---
templateVersion: 2020-01-25
componentName: com.aws.greengrass.HelloWorld
description: hello world from greengrass!
publisher: Amazon
version: 1.0.0
componentType: raw
manifests:
  - platform:
      os: windows
      architecture: x86_64
    configuration:
      schema: s3://some-bucket/my-hello-world-schema.json
      defaultValue: s3://some-bucket/my-hello-world-configurations.json
    lifecycle:
      run:
        python3 {{artifacts:path}}/hello_windows_server.py '{{message.greeting}}'
    artifacts:
      - uri: s3://some-bucket/hello_windows.zip
        digest: d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f
        algorithm: SHA-256
        unarchive: ZIP
    dependencies:
      python3:
        versionRequirement: ^3.5
        dependencyType: soft
  - configuration:
      schema: s3://some-bucket/my-hello-world-schema.json
      defaultValue: s3://some-bucket/my-hello-world-configurations.json
    lifecycle:
      run:
        python3 {{artifacts:path}}/hello_world.py '{{message.greeting}}'
    artifacts:
      - uri: s3://some-bucket/hello_world.py
        digest: d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f
        algorithm: SHA-256
    dependencies:
      python3:
        versionRequirement: ^3.5
```
The topics on this reference are organized by top-level keys in terms of providing component metadata or
 defining platform specific manifest. Top-level keys can have options that support them as sub-topics. This
  maps to the `<key>: <options>: <value>` indent structure of recipe file.

### TEMPLATE VERSION
Define the version of recipe template
```yaml
RecipeTemplateVersion: '2020-01-25'
```
### COMPONENT NAME
Component name identifier, reverse DNS notation is recommended. Component name is unique within a private component
 registry. A private component which has same name occludes public available component.
> note: component name is also used as service name, since component to service is 1:1 mapping.
```yaml
componentName: com.aws.greengrass.HelloWorld
```
### VERSION
Component verison, use [semantic versioning](https://semver.org/) standard
```yaml
version: 1.6.1
```
### DESCRIPTION
Text description of component
```yaml
description: Hello World App for Evergreen
```
### PUBLISHER
Publisher of component
```yaml
publisher: Amazon
```
### COMPONENT TYPE
Describe component runtime mode, support values: `plugin`, `lambda`, `raw`
```yaml
componentType: Amazon
```
### MANIFESTS
Define a list of manifests, a manifest can be specific to one platform or default to every other platform.
#### MANIFEST.PLATFROM
Define the platform the manifest is specifically for.
```yaml
platform:
  os: windows
  architecture: x86_64
```
##### OS
supported operating system [list](to be added).
##### Architecture
supported architecture [list](to be added).

#### MANIFEST.CONFIGURATION
Component author specifies configuration parameters used in lifecycle management scripts, and/or are accessible by
 service runtime, which can both read and write configuration values.
```yaml
configuration:
  schema: s3://some-bucket/my-hello-world-schema.json
  defaultValue: s3://some-bucket/my-hello-world-configurations.json
```
##### Schema
define configuration schema
##### Default Value
provide configuration default values
#### MANIFEST.LIFECYCLE
Specify lifecycle management scripts for component represented service
```yaml
Lifecycle:
  setenv: # apply to all commands to the service.
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
#### MANIFEST.ARTIFACTS
A list of artifacts that component uses as resources, such as binary, scripts, images etc.
```yaml
Artifacts:
    - uri: s3://some-bucket/hello_windows.zip
      digest: d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f
      algorithm: SHA-256
      unarchive: ZIP
```
##### URI
Artifacts are referenced by artifact URIs. Currently Greengrass supports Greengrass repository and s3 as artifact
 storage location.
##### Digest
Calculated cryptographic hash for integrity check
##### Algorithm
Algorithm used for calculating cryptographic hash
##### Unarchive
Indicate whether automatically unarchive artifact
#### MANIFEST.DEPENDENCIES
Describe component dependencies, the versions of dependencies will be resolved during deployment.
> note: Services represented by components will be started/stopped with respect to dependency order.
```yaml
Dependencies:
    shared.python:
      VersionRequirement: ~3.6
      DependencyType: SOFT
```
##### Version Requirement
Specify dependency version requirements, the requirements use NPM-style syntax.
##### Dependency Type
Specify if dependency is `HARD` or `SOFT` dependency. `HARD` means dependent service will be restarted if the dependency
 service changes state. In the opposite, `SOFT` means the service will wait the dependency to start when first
  starting, but will not be restarted if the dependency changes state.

