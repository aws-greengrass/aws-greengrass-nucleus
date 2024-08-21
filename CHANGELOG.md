# Changelog

## v2.11.0

### New features
* Enabled local deployment cancellation
* Enabled configurable failure handling policy
* Nucleus now supporting disk spooler plug in


## v2.10.3

### Bug fixes and improvements
*Fixes an issue where Greengrass could not subscribe to deployment notifications when using the PKCS11 provider

## v2.10.2

### Bug fixes and improvements
* Allows case insensitive parsing of component lifecycle
* Fixes an issue where environment PATH variable was not getting recreated correctly.
* Fix proxy URI encoding for components including stream manager for usernames with special characters.

## v2.10.1

### Bug fixes and improvements
* Revert SDK CRT to fix launch failure on some armv8 processors
* Check configured input stream option before close

## v2.10.0

### New features
* Adds support for MQTT5.
* Adds a mechanism for loading plugin components quickly without scanning.
* Enables Greengrass to save disk space by deleting unused Docker images.
* Allows access to private Amazon ECR repositories in regions other than the AWS IoT Greengrass core region.
* Adds an optional configuration value to define where the IPC socket file should be created on Linux.

### Bug fixes and improvements
* Fixes an issue where rollback leaves certain configuration values in place from a deployment.
* Fixes an issue where the Greengrass nucleus validates for an AWS domain sequence in custom non-AWS credentials and data endpoints.
* Updates multi-group dependency resolution to re-resolve all group dependencies via AWS Cloud negotiation, instead of locking to the active version. This update also removes the deployment error code INSTALLED_COMPONENT_NOT_FOUND.
* Updates the Greengrass nucleus to skip downloading Docker images when they already exist locally.
* Updates the Greengrass nucleus to restart a component install step before timeout expires.
* Additional minor fixes and improvements.

## v2.9.6

## Bug fixes and improvements

* Fixes an issue where a Greengrass deployment fails with the error `LAUNCH_DIRECTORY_CORRUPTED` and a
subsequent device reboot would fail to start Greengrass.
This error may occur when you move the Greengrass device between multiple thing groups with deployments that require Greengrass to restart.

## v2.9.5

### New features

* Adds support for Greengrass nucleus software signature verification.

### Bug fixes and improvements

* Fixes an issue where a deployment fails when the local recipe metadata region doesn't match the Greengrass nucleus launch region. The Greengrass nucleus now renegotiates with the cloud when this happens.
* Fixes an issue where the MQTT message spooler fills up and never removes messages.
* Additional minor fixes and improvements.

## v2.9.4

### Bug fixes and improvements
* Checks for a null message before it drops QOS 0 messages.
* Truncates job status detail values if they exceed the 1024 character limit.
* Updates the bootstrap script for Windows to correctly read the Greengrass root path if that path includes spaces.
* Updates subscribing to AWS IoT Core so that it drops client messages if the subscription response wasn't sent.
* Ensures that the nucleus loads its configuration from backup files when the main configuration file is corrupt or missing.

## v2.9.3

### Bug fixes and improvements
* Ensure MQTT client IDs are not duplicated
* Add more robust file-reading/writing to avoid and recover from corruption
* Retry Docker image pull on specific network-related errors
* Enable the noProxyAddresses option for MQTT connection

## v2.9.2

### Bug fixes and improvements
* Fixes an issue where configuring interpolateComponentConfiguration doesn't apply to an ongoing deployment.
* Uses OSHI to list all child processes.
* Additional minor fixes and improvements.

## v2.9.1

### Bug fixes and improvements
* Restart nucleus when a deployment removes a plugin component.

## v2.9.0

### New features
* Adds the ability to create subdeployments that retry deployments with a smaller subset of devices.
This feature creates a more efficient way to test and resolve unsuccessful deployments.

### Bug fixes and improvements
* Improves support for systems that don't have useradd, groupadd, and usermod.
* Additional minor fixes and improvements.

## v2.8.1

### Bug fixes and improvements
* Fixes an issue where deployment error codes were not generated correctly from Greengrass API errors.
* Fixes an issue where fleet status updates send inaccurate information when a component reaches an ERRORED state during a deployment.
* Fixes an issue where deployments couldn’t complete when Greengrass had more than 50 existing subscriptions.

## v2.8.0

### New features
* Updates the Greengrass nucleus to report a [deployment health status](https://docs.aws.amazon.com/greengrass/v2/developerguide/deployment-health-notifications.html) response that includes detailed error codes when there is a problem deploying components to a core device. For more information, see [Detailed deployment error codes](https://docs.aws.amazon.com/greengrass/v2/developerguide/troubleshooting-deployment.html).
* Updates the Greengrass nucleus to report a [component health status](https://docs.aws.amazon.com/greengrass/v2/developerguide/deployment-health-notifications.html) response that includes detailed error codes when a component enters the BROKEN or ERRORED state. For more information, see [Detailed component status codes](https://docs.aws.amazon.com/greengrass/v2/developerguide/troubleshooting-component.html).
* Expands status message fields to improve cloud availability information for devices
* Improves fleet status service robustness.

### Bug fixes and improvements
* Allows a broken component to reinstall when its configuration changes.
* Fixes an issue where a nucleus restart during bootstrap deployment causes a deployment to fail.
* Fixes an issue in Windows where installation fails when a root path contains spaces.
* Allows the Greengrass nucleus to save deployment queues during shutdown.
* Fixes an issue where a component shut down during a deployment uses the shutdown script of the new version.
* Various shutdown improvements.
* Additional minor fixes and improvements.

## v2.7.0

### New features
* Updates the Greengrass nucleus to send status updates to the AWS IoT Greengrass cloud when the core device applies a local deployment.
* Adds support for client certificates signed by a custom certificate authority (CA), where the CA isn't registered with AWS IoT. To use this feature, you can set the new greengrassDataPlaneEndpoint configuration option to iotdata. For more information, see Use a device certificate signed by a private CA.

### Bug fixes and improvements
* Fixes an issue where the Greengrass nucleus rolls back a deployment in certain scenarios when the nucleus is stopped or restarted. The nucleus now resumes the deployment after the nucleus restarts.
* Updates the Greengrass installer to respect the --start argument when you specify to set up the software as a system service.
* Updates the behavior of SubscribeToComponentUpdates to set the deployment ID in events where the nucleus updated a component.
* Additional minor fixes and improvements.

## v2.6.0

### New features
* Adds support for MQTT wildcards when you subscribe to local publish/subscribe topics. For more information, see [Publish/subscribe local messages](https://docs.aws.amazon.com/greengrass/v2/developerguide/ipc-publish-subscribe.html) and [SubscribeToTopic](https://docs.aws.amazon.com/greengrass/v2/developerguide/ipc-publish-subscribe.html#ipc-operation-subscribetotopic).
* Adds support for recipe variables in component configurations, other than the `component_dependency_name:configuration:json_pointer` recipe variable. You can use these recipes variables when you define a component's `DefaultConfiguration` in a recipe or when you configure a component in a deployment. For more information, see [Recipe variables](https://docs.aws.amazon.com/greengrass/v2/developerguide/component-recipe-reference.html#recipe-variables) and [Use recipe variables in merge updates](https://docs.aws.amazon.com/greengrass/v2/developerguide/update-component-configurations.html#merge-configuration-update-recipe-variables).
* Adds full support for the `*` wildcard in interprocess communication (IPC) authorization policies. You can now specify the `*` character in a resource string to match any combination of characters. For more information, see [Wildcards in authorization policies](https://docs.aws.amazon.com/greengrass/v2/developerguide/interprocess-communication.html#ipc-authorization-policy-wildcards).
* Adds support for custom components to call IPC operations that the Greengrass CLI uses. You can use these IPC operations to manage local deployments, view component details, and generate a password that you can use to sign in to the [local debug console](https://docs.aws.amazon.com/greengrass/v2/developerguide/local-debug-console-component.html). For more information, see [IPC: Manage local deployments and components](https://docs.aws.amazon.com/greengrass/v2/developerguide/ipc-local-deployments-components.html).

### Bug fixes and improvements
* Fixes an issue where dependent components wouldn't react when their hard dependencies restart or change states in certain scenarios.
* Improves error messages that the core device reports to the AWS IoT Greengrass cloud service when a deployment fails.
* Fixes an issue where the Greengrass nucleus applied a thing deployment twice in certain scenarios when the nucleus restarts.
* Additional minor fixes and improvements.

## v2.5.6

### New features
* Adds support for hardware security modules that use ECC keys. You can use a hardware security module (HSM) to securely store the device's private key and certificate. For more information, see [Hardware security integration](https://docs.aws.amazon.com/greengrass/v2/developerguide/hardware-security.html).

### Bug fixes and improvements
* Fixes an issue where the deployment never completes when you deploy a component with a broken install script in certain scenarios.
* Improves performance during startup.
* Additional minor fixes and improvements.

## v2.5.5

### New features
* Adds the GG_ROOT_CA_PATH environment variable for components, so you can access the root certificate authority (CA) certificate in custom components.

### Bug fixes and improvements:
* Adds support for Windows devices that use a display language other than English
* Updates how nucleus parses Boolean installer flags, so you can specify a Boolean flag without a value to set the value to true. For example, you can now specify --provision instead of --provision true to install with automatic resource provisioning.
* Fixes an issue where the core device didn't report its status to the AWS IoT Greengrass cloud service after provisioning in certain scenarios
* Additional minor fixes and improvements

## v2.5.4

This version contains bug fixes and improvements.

## v2.5.3

### New features
- Adds support for hardware security integration. You can use a hardware security module (HSM) to securely store the device's private key and certificate.

### Bug fixes and improvements:
- Fixes an issue with runtime exceptions while the nucleus establishes MQTT connections with AWS IoT Core.

## v2.5.2

### Bug fixes and improvements

* Fixes an issue where after the Greengrass Nucleus updates, the Windows service fails to start again after you stop it or reboot the device.

## v2.5.1

### Bug fixes and improvements
* Adds support for 32-bit versions of the Java Runtime Environment (JRE) on Windows.
* Changes thing group removal behavior for core devices whose AWS IoT policy doesn't grant the greengrass:ListThingGroupsForCoreDevice permission. With this version, the deployment continues, logs a warning, and doesn't remove components when you remove the core device from a thing group. For more information, see Deploy AWS IoT Greengrass components to devices.
* Fixes an issue with system environment variables that the Greengrass nucleus makes available to Greengrass component processes. You can now restart a component for it to use the latest system environment variables.

## v2.5.0

### New features:
* Adds support for running Greengrass nucleus on Windows.
* Changes behavior for when the core device is removed from a thing group. Before, that thing group's components would remain on the device. Now, the Greengrass nucleus removes that thing group's components from the device in the next deployment. This change requires that the core device's AWS IoT policy grants the `greengrass:ListThingGroupsForCoreDevice` permission.
* Adds support for HTTPS proxy configurations
* Adds configuration 'httpClient' for SDK client socket and connection timeouts

### Bug fixes and improvements:
* Fixes the bootstrap lifecycle option to restart the core device from a component.
* Adds support for hyphens in recipe variables.
* Fixes IPC authorization for on-demand Lambda functions.
* Improves log messages and changes non-critical logs from `INFO` to `DEBUG` level, so logs are more useful.
* Removes the `iot:DescribeCertificate` permission from the default token exchange role policy, because it isn't used.
* Fixes an issue so the automatic provisioning script doesn't require the `iam:GetPolicy` permission if `iam:CreatePolicy` is available.
* Additional minor fixes and improvements.

## v2.4.0

### New features:

* Add ability to configure CPU and memory system resource limits for generic components. You can configure default limits, and you can configure limits for each component when you create a deployment.
* Add IPC operations that you can use to pause and and resume generic components.
* Add support for custom provisioning plugins that you can run during installation to provision a device and obtain a device identity (device certificate, private key and rootCA certificate).

### Bug fixes and improvements:

* Update logging config on startup. This fixes an issue where the logging configuration wasn't applied on startup.
* Update nucleus loader symlink to the component store during installation, so you can delete nucleus artifacts that you downloaded to install the nucleus.
* Add an optional thing-policy-name argument for device provisioning, so you can specify an existing or custom IoT policy when you provision a core device.

## v2.3.0

### New features:
* Adds support for deployment configuration documents up to 10 MB, up from 7 KB (for deployments that target things) or 31 KB (for deployments that target thing groups). To use this feature, a core device's AWS IoT policy must allow the greengrass:GetDeploymentConfiguration permission. If you used the AWS IoT Greengrass Core software installer to provision resources, your core device's AWS IoT policy allows greengrass:*, which includes this permission. For more information, see Device authentication and authorization for AWS IoT Greengrass.
* Adds the iot:thingName recipe variable. You can use this recipe variable to get the name of the core device's AWS IoT thing in a recipe. For more information, see Recipe variables.

## v2.2.0

### New features:

* Add IPC operations for local shadow management.

### Bug fixes and improvements:

* Reduce the size of the JAR file.
* Reduce memory usage.
* Fix issues where the log configuration wasn’t updated in certain cases.

*Note*: The source code for this version of the nucleus includes experimental Windows features that are under development and not ready for production use.

## v2.1.0

### New features:

* Support downloading Docker images from private repositories in Amazon ECR. 
* Add the following parameters to customize the MQTT configuration on core devices:
    * maxInFlightPublishes – The maximum number of unacknowledged MQTT QoS 1 messages that can be in flight at the same time.
    * maxPublishRetry – The maximum number of times to retry a message that fails to publish.
* Add the fleetstatusservice configuration parameter to configure the interval at which the core device publishes device status to the AWS Cloud.

### Bug fixes and improvements:

* Fix an issue that caused shadow deployments to be duplicated when the nucleus restarts.
* Fix an issue that caused the nucleus to crash when it encountered a service load exception.
* Improve component dependency resolution to fail a deployment that includes a circular dependency.
* Fix an issue that prevented a plugin component from being redeployed if that component had been previously removed from the core device.
* Fix an issue that caused the HOME environment variable to be set to the /greengrass/v2/work directory for Lambda components or for components that run as root. The HOME variable is now correctly set to the home directory for the user that runs the component. 
* Additional minor fixes and improvements.


## v2.0.5

### Bug fixes and improvements:
* Correctly route traffic through a configured network proxy when downloading AWS-provided components.
* Use the correct Greengrass data plane endpoint in AWS China Regions.

## v2.0.4

### Note to users:
* Automatic provisioning using `--provision true` now requires `iam:GetPolicy` and `sts:GetCallerIdentity`. See
  [our documentation](https://docs.aws.amazon.com/greengrass/v2/developerguide/install-greengrass-core-v2.html#provision-minimal-iam-policy) for the full updated set of minimum permissions.

### New features:
* Enable HTTPS traffic over port 443. You use the new greengrassDataPlanePort configuration parameter for the nucleus component to configure HTTPS communication to travel over port 443 instead of the default port 8443. (#811)(328ad0a9)
* Add the work path recipe variable. You can use this recipe variable to get the path to components' work folders, which you can use to share files between components and their dependencies. (0fa011b)

### Bug fixes and improvements:
* Correctly handle the cancellation of a deployment that has not yet been registered successfully. (#799)(95ca6e23) 
  closes [#798](https://github.com/aws-greengrass/aws-greengrass-nucleus/issues/798)
* Prevent the creation of the token exchange IAM role policy if a role policy already exists. (#805)(893a8e17) 
  closes [#802](https://github.com/aws-greengrass/aws-greengrass-nucleus/issues/802)
* Update the configuration to remove older entries with newer timestamps when rolling back a deployment. (#824)(1a093bb5)
* Additional fixes and improvements.
