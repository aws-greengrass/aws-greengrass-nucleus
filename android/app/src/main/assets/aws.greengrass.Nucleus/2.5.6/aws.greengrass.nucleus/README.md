# Greengrass Nucleus
![Java CI](https://github.com/aws-greengrass/aws-greengrass-nucleus/workflows/Java%20CI/badge.svg?branch=main)

### *Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.*
#### *SPDX-License-Identifier: Apache-2.0*

The Greengrass nucleus component provides functionality for device side orchestration of deployments and lifecycle management for execution of Greengrass components and applications. This includes features such as starting, stopping, and monitoring execution of components and apps, interprocess communication server for communication between components, component installation and configuration management. It manages the model that describes the
 software running on the device.  The model is a dependency graph of *services*.  Services have three primary aspects:

* Configuration
* Dependencies on other services
* A set of lifecycle phases in the form of a finite state machine.

A *service* may have processes, threads, code, network connections, ... But not
necessarily.  Some have all of these, some have only one.

You can think of the nucleus as a mash-up of `make`, a super-lightweight publish/subscribe system, and a small
 hierarchic key-value data store.  The various services have continuously varying states that the nucleus monitors and manages.
   A dependent service is not started until it's dependencies are started, and if they become unstable, the dependent service is notified.
     The internal interconnections are handled via dependency injection. Restarts are managed automatically.

When configuration changes, all users of them are notified.  Everything adapts continuously.

### A quick tour through com.aws.greengrass
1. [**config**](src/main/java/com/aws/greengrass/config) Manages the system configuration (model).  It's
 fundamentally a hierarchic key-value store with timestamps.  It can be serialized to/from yaml, json, or a
  transaction log.  The transaction log can be replayed to reconstruct the config, or streamed live to another
   process to maintain a mirror. The terminology is borrowed from the world of publish/subscribe systems.  Config
    values can have validators and watcher.
2. [**dependency**](src/main/java/com/aws/greengrass/dependency) The dependency injection framework.  The meat is in
 `context.java` which contains a Map of known objects, and the ability to get (and magically create) objects from the
  Context.  When an object is created by the framework, it does dependency injection.  If the created object
   participates in the Lifecycle framework, its lifecycle is initiated.  This feeds the Lifecycle dependency graph.
3. [**lifecyclemanager**](src/main/java/com/aws/greengrass/lifecyclemanager) Ties the model to Lifecycle objects in the dependency graph.  The
 primary class is `GreengrassService`, which contains most of the state transition logic.  `GenericExternalService` is a
  subclass that implements a service whose behavior is defined by commands and scripts.  Either of these classes may be
   subclassed to provide services whose behavior is defined by code running within Greengrass.
4. [**util**](src/main/java/com/aws/greengrass/util) A grab-bag of useful utilities.

### Learn more
1. [Greengrass Nucleus Configuration Schema](README_CONFIG_SCHEMA.md)
1. [Data Model - Component Recipe](https://github.com/aws-greengrass/aws-greengrass-component-common/blob/main/RECIPE_REFERENCE.md)
1. [Configure a component](CONFIGURE_COMPONENT_README.md)
