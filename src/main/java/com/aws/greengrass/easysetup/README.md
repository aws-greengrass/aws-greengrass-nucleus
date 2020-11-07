# Easy setup script
The setup script is intended to give a brand new user of Greengrass to get started with Greengrass device quickly.
As part of that experience the user can get a fat jar for the Greengrass Nucleus, the script can launch the Nucleus
 with the customer's provided config if desired, optionally provision the test device as an AWS IoT Thing, create and
 attach policies and certificates to it, create TES role and role alias or uses existing ones and attaches
 them to the IoT thing certificate.


## Getting the jar
# TODO : link aws documentation for getting the Nucleus
```
mvn clean package
```
Check the build logs to see where the jar lives

## Using the script
OPTIONS
```
--config, -i                    Path to the configuration file to start Greengrass Nucleus with
--root, -r                      Path to the directory to use as the root for Greengrass
--thing-name, -tn               Desired thing name to register the device with in AWS IoT cloud
--thing-group-name, -tgn        Desired thing group to add the IoT Thing into
—-tes-role-name, -trn           Name of the IAM role to use for TokenExchangeService for the device to talk
                                to AWS services, the default is GreengrassV2TokenExchangeRole.
                                If the role does not exist in your account it will be created with a default policy
                                called GreengrassV2TokenExchangeRoleAccess, by default it DOES NOT have access
                                to your S3 buckets where you will host your private component artifacts, so you need
                                to add your component artifact S3 buckets/objects to that role in your AWS account.
--tes-role-alias-name, -tra     Name of the RoleAlias to attach to the IAM role for TES in the AWS
                                IoT cloud, if the role alias does not exist then it will be created in your AWS account
--provision, -p                 Y/N Indicate if you want to register the device as an AWS IoT thing
--aws-region, -ar               AWS region where the resources should be looked for/created
--setup-system-nucleus, -ss     Y/N Indicate if you want to setup Greengrass as a system service
--component-default-user, -u    Name of the default user that will be used to run component
--component-default-group, -g   Name of the default group that will be used to run component
--deploy-dev-tools, -d          Y/N Indicate if you want to deploy Greengrass CLI and the HttpDebugView components
```

## Set up steps with Greengrass zip file
This workflow has been implemented for Ubuntu. Use as a reference.
```
# Move aws.greengrass.nucleus.zip to test device
# Set up aws creds
unzip aws.greengrass.nucleus.zip -d GreengrassCore
java -Droot=~/gg_home -Dlog.level=WARN -jar ./GreengrassCore/lib/Greengrass.jar --provision true --aws-region us-east-1 --thing-name <test-device> -tra <test-role-alias> -ss true

# Verify the setup
tree ~/gg_home/
sudo service greengrass status
sudo journalctl -u greengrass -f
```
