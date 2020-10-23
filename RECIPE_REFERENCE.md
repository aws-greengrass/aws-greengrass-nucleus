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
ComponentConfiguration:
  DefaultConfiguration:
    singleLevelKey: default value of singleLevelKey
    args:
      windowsArg: Hello Windows
      linuxArg: Hello Linux

ComponentDependencies:
  variant.Python3:
    VersionRequirement: ^3.5
    DependencyType: SOFT

Manifests:
  - Platform:
      os: windows
      architecture: x86_64
    Lifecycle:
      Run:
        python3 {{artifacts:path}}/hello_windows_server.py {configuration:/args/windowsArg}
    Artifacts:
      - URI: s3://some-bucket/hello_windows.zip
        Unarchive: ZIP
  - Platform:
      os: linux
      architecture: arm
    Lifecycle:
      Run:
        python3 {{artifacts:path}}/hello_world.py {configuration:/args/linuxArg}
    Artifacts:
      - URI: s3://some-bucket/hello_world.py
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
Describe component runtime mode, support values: `aws.greengrass.plugin`, `aws.greengrass.lambda`, `aws.greengrass.generic`, `aws.greengrass.nucleus`, default is `aws.greengrass.generic`
```yaml
ComponentType: aws.greengrass.generic
```

#### Component Dependencies
Describe component dependencies, the versions of dependencies will be resolved during deployment.
> note: Services represented by components will be started/stopped with respect to dependency order.

```yaml
ComponentDependencies:
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
  
#### ComponentConfiguration
##### ComponentConfiguration.DefaultConfiguration
Each Greengrass V2 component could define its own default configuration which would be used by default.
The configuration is a free-form hierarchical structure. It could be used by the recipe's lifecycle section with dynamic interpolation 
as well as the component code and logic.

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

---
#### Manifest.Lifecycle with recipe variables
Recipe variables expose information from the component and kernel for you to use in your recipes. For example, you can use recipe variables to pass component configurations to a lifecycle script that exists as an artifact.

Recipe variables use {recipe_variable} syntax. The single curly braces indicate a recipe variable and will be replaced at runtime.

##### Use Component's own variables
{\<namespace\>:\<key\>}

The following recipe variables:

1. {configuration:\<json pointer\>} The value of a configuration at the provided JSON pointer location for the component. 

For example, the {configuration:/path/list/0} recipe variable retrieves the value at the location of `/path/list/0` from the configuration. 

Note a JSON pointer could point to 4 different possible node type, including:
1. Value node: the place holder will be replacedd by the **the text representation for that value**.
2. Container node: the place holder will be replacedd by the serialized JSON String representation for that container. Note the JSON string
usually contains double quotes. If you are using it in the command line, make sure you escape it appropriately.
3. `null`: the placeholder will be replaced as: **null**
4. missing node: the placeholder **will remain**.

You can use this variable to provide a configuration value to a script that you run in the component lifecycle.

2. {artifacts:path}
The root path of the artifacts for the component that this recipe defines. When a component installs, AWS IoT Greengrass copies the component's artifacts to the folder that this variable exposes. 
You can use this variable to identify the location of a script to run in the component lifecycle.

3. {artifacts:decompressedPath}
The root path of the decompressed archive artifacts for the component that this recipe defines. When a component installs, AWS IoT Greengrass unpacks the component's archive artifacts to the folder that this variable exposes. 
You can use this variable to identify the location of a script to run in the component lifecycle. 
Each artifact unzips to a folder within the decompressed path, where the folder has the same name as the artifact minus its extension. For example, a ZIP artifact named models.zip unpacks to the {{artifacts:decompressedPath}}/models folder

##### Use Direct Dependencies' variables
If a component has dependencies, it sometimes require reading its dependencies's info at runtime, such as configuration and artifact path.
Syntax: {\<componentName\>:\<namespace\>:\<key\>}

Similarly, you could use the variables above.
1. {\<componentName\>:configuration:\<json pointer\>}
1. {\<componentName\>:artifacts:path}
1. {\<componentName\>:artifacts:decompressedPath}

If you refer to a componentName that is not a direct dependency, **the placeholder will remain**.

#### Global recipe variables
Global recipe variables could be used by any component.

1. {kernel:root}
The absolute root path that the kernel is running at runtime. 

---
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
