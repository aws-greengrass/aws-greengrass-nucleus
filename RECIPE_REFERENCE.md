# Component Recipe Reference
## Reference and guidelines
This reference describes version 2020-xx-xx of component recipe file format.

Component recipe is a single yaml/json file for the component author to define component deployment and runtime
 characteristics in the AWS Greengrass ecosystem.
## Recipe file structure and examples
Here is a sample recipe file in yaml format which defines a simple HelloWorld application can run on AWS Greengrass
 managed devices. It defines a manifest for x86_64 windows as well as a manifest for arm32 linux.
 
 > recipe key name use [PascalCase](https://wiki.c2.com/?PascalCase), and is case-sensitive
 
```yaml
---
RecipeFormatVersion: 2020-01-25
ComponentName: com.aws.greengrass.HelloWorld
ComponentVersion: 1.0.0
ComponentDescription: hello world from greengrass!
ComponentPublisher: Amazon
ComponentType: aws.greengrass.generic
Manifests:
  - Platform:
      os: windows
      architecture: x86_64
    Lifecycle:
      Run:
        python3 {{artifacts:path}}/hello_windows_server.py
    Artifacts:
      - URI: s3://some-bucket/hello_windows.zip
        Unarchive: ZIP
    Dependencies:
      variant.Python3:
        VersionRequirement: ^3.5
        DependencyType: SOFT
  - Platform:
      os: linux
      architecture: arm
    Lifecycle:
      Run:
        python3 {{artifacts:path}}/hello_world.py
    Artifacts:
      - URI: s3://some-bucket/hello_world.py
    Dependencies:
      variant.Python3:
        VersionRequirement: ^3.5
```
The topics on this reference are organized by top-level keys in terms of providing component metadata or
 defining platform specific manifest. Top-level keys can have options that support them as sub-topics. This
  maps to the `<key>: <options>: <value>` indent structure of recipe file.

### Recipe Format Version
Define the version of recipe format
```yaml
RecipeFormatVersion: 2020-01-25
```
### Component Name
Component name identifier, reverse DNS notation is recommended. Component name is unique within a private component
 registry. A private component which has same name occludes public available component.
> note: component name is also used as service name, since component to service is 1:1 mapping.

```yaml
ComponentName: com.aws.greengrass.HelloWorld
```
### Component Version
Component verison, use [semantic versioning](https://semver.org/) standard
```yaml
ComponentVersion: 1.0.0
```
### Component Description
Text description of component
```yaml
ComponentDescription: Hello World App
```
### Component Publisher
Publisher of component
```yaml
ComponentPublisher: Amazon
```
### Component Type
Describe component runtime mode, support values: `aws.greengrass.plugin`, `aws.greengrass.lambda`, `aws.greengrass.generic`, default is `aws.greengrass.generic`
```yaml
ComponentType: aws.greengrass.generic
```
### Manifests
Define a list of manifests, a manifest is specific to one platform or default to every other platform.
#### Manifest.Platform
Define the platform the manifest is specifically for.
```yaml
Platform:
  os: windows
  architecture: x86_64
```
* supported operating system [list](to be added).
* supported architecture [list](to be added).

#### Manifest.Lifecycle
Specify lifecycle management scripts for component represented service
```yaml
Lifecycle:
  Setenv: # apply to all commands to the service.
    <key>: <defaultValue>
        
  Install:
    Skipif: onpath <executable>|exists <file>
    Script:
    Timeout: # default to be 120 seconds.
    Environment: # optional
      <key>: <overrideValue>
    
  Startup: # mutually exclusive from 'run'
    Script: # eg: brew services start influxdb
    Timeout: # optional
    Environment:  # optional, override
      
  Run: # mutually exclusive from 'startup'
    Script:
    Environment: # optional, override
    Timeout: # optional
    Periodicity: # perodically run the command
    
  Shutdown: # can co-exist with both startup/run
    Script:
    Environment: # optional, override
    Timeout: # optional
  
  Healthcheck: # do health check when service is in Running
    Script: # non-zero exit trigger error
    RecheckPeriod: # optional, default to be 0
    Environment: # override
    
  Recover:
    Script: # will be run every time service enters error.
    Environment: # optional, override
    # referring to https://docs.docker.com/v17.12/compose/compose-file/#restart_policy
    RetryPolicy:
      Delay: # default to be 0. Time to wait between retry.
      MaxAttempts: # default to be infinite. After N times of error, service enter Broken state.
      Window: # how long to wait before deciding if a restart has succeeded
  
  CheckIfSafeToUpdate:
    RecheckPeriod: 5
    Script: 
    
  UpdatesCompleted:
    Script:
```
#### Manifest.Artifacts
A list of artifacts that component uses as resources, such as binary, scripts, images etc.
```yaml
Artifacts:
    - URI: s3://some-bucket/hello_windows.zip
      Unarchive: ZIP
```
##### URI
Artifacts are referenced by artifact URIs. Currently Greengrass supports Greengrass repository and s3 as artifact
 storage location.
##### Unarchive
Indicate whether automatically unarchive artifact
#### Manifest.Dependencies
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
