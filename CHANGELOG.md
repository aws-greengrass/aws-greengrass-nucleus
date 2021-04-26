# Changelog

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
