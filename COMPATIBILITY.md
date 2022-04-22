# Compatibility Guidelines

## Background

The purpose of this document is to define what is a backward incompatible change in the context of Greengrass development and provide guidelines for contributors on how to prevent it when developing changes.

## Definition

A backward incompatible change (or breaking change) is a change in Greengrass-provided resources (such as software, APIs, and policies) that *requires* customers to make a corresponding change in order to avoid disruption of existing workflow.

**Consider backward compatibility a feature that we have to design for.** As a tenet, never make backwards incompatible changes in incremental patch or minor version of the software, no matter how small the changes seem.

Any change that *removes* something from an interface or adds a new mandatory requirement for its use is considered backward incompatible. Backward incompatible changes include simple modifications such as changing a type visibility from public to private, or drastic changes such as switching a database.

|Element	|Breaking changes	|
|---	|---	|
|Type	|rename, move, move and rename, remove, lost visibility, add final modifier, remove static modifier, change in supertype, remove supertype	|
|Method	|move, rename, remove, push down, inline, change in parameter list, change in exception list, change in return type, lost visibility, add final modifier, remove static modifier	|
|Field	|remove, move, push down field, change in default value, change in type field, lost visibility, add final modifier	|

|Element	|Non-breaking changes	|
|---	|---	|
|Type	|add, extract supertype, gain visibility, remove final modifier, add static modifier, add supertype, deprecated type	|
|Method	|pull up, gain visibility, remove final modifier, add static modifier, deprecated method, add, extract	|
|Field	|pull up, add, deprecated field, gain visibility, remove final modifier	|

Referenced from [Why and How Java Developers Break APIs](https://arxiv.org/pdf/1801.05198.pdf).

## Greengrass public interfaces

Backward incompatibility is a result of modifying a software’s public interfaces. In order to define backward incompatible changes in context of Greengrass development, we should first understand how customers interact with versioned Greengrass resources.

1. Customers deploy a set of Greengrass core **components** with any version combinations satisfying dependency requirements.
    1. Plugin components are loaded into Nucleus JVM and import Nucleus public methods/types.
    2. Nucleus acts as an IPC server and other components as an IPC client.
    3. Components read and export data, whose format may differ between versions.
    4. Component recipes define the lifecycle, dependencies, and configuration of components. Components take configuration as input for parameters.
2. Customers configure a static set of **permissions and resources** required for certain component operations.
    1. Cloud requirements: IAM role with a list of policies and resources
    2. Local requirements: Java/Python version, key/certificate location, endpoints, etc.
3. Customers write applications to call Greengrass **APIs**
    1. AWS API, IoT device SDK, Stream manager SDK
    2. Any change in parameters, request fields (e.g. deployment document, recipe format), and response fields of these APIs may break customer.

## How do I proactively prevent breaking changes in development?

### Component development

1. When modifying/removing a public method/type/field of Nucleus, developers should proactively search and verify if any other components import that method/type/field.
2. We should limit Nucleus’ public interfaces. When adding new classes/methods to Nucleus, be judicious of whether they need to be public.
3. The Nucleus GitHub repository has a [script](/.github/scripts/binaryCompatibility.py) to check binary compatibility for every PR and prints comments if incompatible change detected. Reviewers should also treat any comment with diligence and evaluate how it influences other plugin components.
4. Treat component recipes (configuration parameters, lifecycle scripts, dependencies, etc.) as API request fields. Any modification should go through the same evaluation process.
5. External libraries notify breaking changes in their changelogs. When updating any external library version in component code, developers and PR reviewers should examine its changelogs to compare between old and new versions.
6. If a unit test, integration test, or UAT failed after a change is introduced, evaluate if it’s a regression first.

### Permissions and resources

1. Cloud policies are associated with API calls. In development, evaluate the permission requirements when a new API call is added.
2. In testing, avoid using wildcard in policy templates; only use minimal IoT policies required for each test case.
3. Local requirements are recorded in each component’s public documentation. Development environment should align with minimum requirements.
