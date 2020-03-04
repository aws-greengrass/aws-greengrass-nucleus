# Evergreen Kernel
![Java CI](https://github.com/aws/aws-greengrass-kernel/workflows/Java%20CI/badge.svg?branch=master)

### *Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.*
#### *SPDX-License-Identifier: Apache-2.0*

This is the kernel of AWS's Evergreen IoT device management framework.  It manages the model that describes the software running on the device.  The model is a dependency graph of *services*.  Services have three primary aspects:

* Parameters
* Dependencies on other services
* A set of lifecycle phases in the form of a finite state machine.

> *A note on the term* **service**:  It's not great.  It just sucks the least of any we've thought of. It isn't too overloaded with connotations.  Suggestions welcomed.

A *service* may have processes, threads, code, network connections, ... But not
necessarily.  Some have all of these, some have only 1.

You can think of the kernel as a mashup of `make`, a super-lightweight publish/subscribe system, and a small hierarchic key-value data store.  The various services have continuously varying states that the kernel monitors and manages.  A dependent service is not started until it's dependencies are started, and if they become unstable, the dependent service is notified.  The internal interconnections are handled via dependency injection. Restarts are managed automatically.

When parameters changes, all users of them are notified.  Everything adapts continuously.

Supposedly  **&#129327;**

Error handling is woefully inadequate, *for now*.

### A quick tour through com.aws.iot.evergreen
1. [**config**](src/main/java/com/aws/iot/evergreen/config) Manages the system configuration (model).  It's fundamentally a hierarchic key-value store with timestamps.  It can be serialized to/from yaml, json, or a transaction log.  The transaction log can be replayed to reconstruct the config, or streamed live to another process to maintain a mirror. The terminology is borrowed from the world of publish/subscribe systems.  Node values can have validators and watcher.
2. [**dependency**](src/main/java/com/aws/iot/evergreen/dependency) The dependency injection framework.  The meat is in `context.java` which contains a Map of known objects, and the ability to get (and magically create) objects from the Context.  When an object is created by the framework, in does dependency injection.  If the created object participates in the Lifecycle framework, its lifecycle is initiated.  This feeds the Lifecycle dependency graph.
3. [**kernel**](src/main/java/com/aws/iot/evergreen/kernel) Ties the model to Lifecycle objects in the dependency graph.  The primary class is `EvergreenService`, which contains most of the state transition logic.  `GenericExternalService` is a subclass that implements a service whose behavior is defined by bash scripts.  Either of these classes may be subclassed to provide services whose behavior is defined by code running within Evergreen.
4. [**util**](src/main/java/com/aws/iot/evergreen/util) A grab-bag of useful utilities.

You'll probably find the coding style to be a trifle odd.  It is very paranoid about failures and tries to catch, cope with, and (maybe) repair failures locally.  Mike Duigou did a nice talk on this topic: [Always Be Running: long running and fault tolerant java services](https://youtu.be/agXce0lSo60).

### Building
This project has continuous integration implemented using AWS CodeBuild. For each push to the master branch, the library is built and deployed to a private maven repository.

You can set this up in your project as follows: 

```xml
<repositories>
    <repository>
        <id>evergreen-dev-snapshot</id>
        <name>Kernel Snapshot</name>
        <url>https://decmzyi1cnv6r.cloudfront.net/snapshot</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>com.aws.iot</groupId>
    <artifactId>evergreen-kernel</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```
