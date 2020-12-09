# Greengrass Nucleus
![Java CI](https://github.com/aws-greengrass/aws-greengrass-nucleus/workflows/Java%20CI/badge.svg?branch=main)

### *Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.*
#### *SPDX-License-Identifier: Apache-2.0*

This is the nucleus of AWS's Greengrass IoT device management framework.  It manages the model that describes the
 software running on the device.  The model is a dependency graph of *services*.  Services have three primary aspects:

* Parameters
* Dependencies on other services
* A set of lifecycle phases in the form of a finite state machine.

A *service* may have processes, threads, code, network connections, ... But not
necessarily.  Some have all of these, some have only 1.

You can think of the nucleus as a mashup of `make`, a super-lightweight publish/subscribe system, and a small hierarchic key-value data store.  The various services have continuously varying states that the nucleus monitors and manages.  A dependent service is not started until it's dependencies are started, and if they become unstable, the dependent service is notified.  The internal interconnections are handled via dependency injection. Restarts are managed automatically.

When parameters changes, all users of them are notified.  Everything adapts continuously.

### A quick tour through com.aws.greengrass
1. [**config**](src/main/java/com/aws/greengrass/config) Manages the system configuration (model).  It's fundamentally a hierarchic key-value store with timestamps.  It can be serialized to/from yaml, json, or a transaction log.  The transaction log can be replayed to reconstruct the config, or streamed live to another process to maintain a mirror. The terminology is borrowed from the world of publish/subscribe systems.  Node values can have validators and watcher.
2. [**dependency**](src/main/java/com/aws/greengrass/dependency) The dependency injection framework.  The meat is in `context.java` which contains a Map of known objects, and the ability to get (and magically create) objects from the Context.  When an object is created by the framework, in does dependency injection.  If the created object participates in the Lifecycle framework, its lifecycle is initiated.  This feeds the Lifecycle dependency graph.
3. [**lifecyclemanager**](src/main/java/com/aws/greengrass/lifecyclemanager) Ties the model to Lifecycle objects in the dependency graph.  The
 primary class is `GreengrassService`, which contains most of the state transition logic.  `GenericExternalService` is a
  subclass that implements a service whose behavior is defined by bash scripts.  Either of these classes may be
   subclassed to provide services whose behavior is defined by code running within Greengrass.
4. [**util**](src/main/java/com/aws/greengrass/util) A grab-bag of useful utilities.

You'll probably find the coding style to be a trifle odd.  It is very paranoid about failures and tries to catch, cope with, and (maybe) repair failures locally.  Mike Duigou did a nice talk on this topic: [Always Be Running: long running and fault tolerant java services](https://youtu.be/agXce0lSo60).
