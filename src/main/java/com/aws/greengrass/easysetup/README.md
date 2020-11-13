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
--help, -h                      (Optional) Show this help information and then exit.
--version, -v                   (Optional) Show the version of the AWS IoT Greengrass Core software and then exit.
--aws-region, -ar               The AWS Region to use. The AWS IoT Greengrass Core software uses this Region to retrieve or
                                create the AWS resources that it requires.
--root, -r                      (Optional) The path to the folder to use as the root for the AWS IoT Greengrass Core software. 
                                Defaults to ~/.greengrass.
--config, -i                    (Optional) The path to the configuration file with which to run the AWS IoT Greengrass Core
                                software. Defaults to config/config.yaml in the root folder.
--provision, -p                 (Optional) Specify true or false. If this argument is true, then the AWS IoT Greengrass Core
                                software registers this device as an AWS IoT thing and provisions the AWS resources that
                                the software requires. The software provisions an AWS IoT thing, (optional) an AWS IoT
                                thing group, an IAM role, and an AWS IoT role alias. Defaults to false.
--thing-name, -tn               (Optional) The name of the AWS IoT thing as which to register this core device. If the thing
                                with this name doesn't exist in your AWS account, then the AWS IoT Greengrass Core software
                                create it. Defaults to GreengrassV2IotThing_ plus a random UUID.
--thing-group-name, -tgn        (Optional) The name of the AWS IoT thing group to which to add this core device's AWS IoT
                                thing. If a deployment targets this thing group, this core device receives that deployment
                                when it connects to AWS IoT Greengrass. If the thing group with this name doesn't exist in your
                                AWS account, then the AWS IoT Greengrass Core software creates it. Defaults to no thing group.
—-tes-role-name, -trn           (Optional) The name of the IAM role to use to acquire AWS credentials that let the device interact
                                with AWS services. If the role with this name doesn't exist in your AWS account, then the AWS IoT
                                Greengrass Core software creates it with the GreengrassV2TokenExchangeRoleAccess policy. This role
                                DOES NOT have access to your S3 buckets where you host component artifacts. This means that you
                                must add permissions to your artifacts' S3 buckets and objects when you create a component.
                                Defaults to GreengrassV2TokenExchangeRole.
--tes-role-alias-name, -tra     (Optional) The name of the AWS IoT role alias that points to the IAM role that provides AWS
                                credentials for this device. If the role alias with this name doesn't exist in your AWS account,
                                then the AWS IoT Greengrass Core software creates it and points it to the IAM role that you specify.
                                Defaults to GreengrassV2TokenExchangeRoleAlias.
--setup-system-service, -ss     (Optional) Specify true or false. If this argument is true, then the AWS IoT Greengrass Core software
                                sets itself up as a system service that runs when this device boots. The system service name is
                                greengrass. Defaults to false.
--component-default-user, -u    (Optional) The name of ID of the system user and group that the AWS IoT Greengrass Core software uses
                                to run components. This argument accepts the user and group separated by a colon, where the group is
                                optional. For example, you can specify ggc_user:ggc_group or ggc_user.
                                * If you run as root, this defaults to the user and group that the config file defines. If the
                                config file doesn't define a user and group, then this defaults to ggc_user:ggc_group. 
                                If ggc_user or ggc_group don't exist, then the  software creates them.
                                * If you run as a non-root user, the AWS IoT Greengrass Core software uses that user run components.
                                * If you don't specify a group, the AWS IoT Greengrass Core software uses the primary group of the system user.
--deploy-dev-tools, -d          (Optional) Specify true or false. If this argument is true, then the AWS IoT Greengrass Core software
                                retrieves and deploys the Greengrass CLI and HTTP debug view components. Specify true to set up this
                                core device for local development. Specify false to set up this core device in a production environment.
                                Defaults to false.
--start, -s                     (Optional) Specify true or false. If this argument is true, then the AWS IoT Greengrass Core software runs
                                setup steps, (optional) provisions resources, and starts the software. If this argument is false, the software
                                only runs setup steps and (optional) provisions resources. Defaults to true.
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
