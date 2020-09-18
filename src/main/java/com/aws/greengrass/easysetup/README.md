# Easy setup script
The setup script is intended to give a brand new user of Greengrass to get started with Greengrass device quickly.
As part of that experience the user can get a fat jar for the Greengrass kernel, the script can launch the kernel
 with the customer's provided config if desired, optionally provision the test device as an AWS IoT Thing, create and
 attach policies and certificates to it, optionally create TES role and role alias or uses existing ones and attaches
 them to the IoT thing certificate.


## Getting the jar
Eventually we want to distribute the jar over CDN but while that strategy is being worked on currently a user needs
 to check out the repository and build it, use the following command for that
```
mvn clean package
```
Check the build logs to see where the jar lives

## Using the script
OPTIONS
```
	--config, -i			Path to the configuration file to start Greengrass kernel with
	--root, -r			Path to the directory to use as the root for Greengrass
	--thing-name, -tn		Desired thing name to register the device with in AWS IoT cloud, ignored if --provision option is false/not specified
    --thing-group-name, -tgn    Desired thing group to add the IoT Thing into
	--policy-name, -pn 		Desired name for IoT thing policy, ignored if --provision option is false/not specified
	—-tes-role-name, -trn 	        Name of the IAM role to use for TokenExchangeService for the device to talk to
                                        AWS services, if the role does not exist then it will be created in your AWS
                                        account, ignored if --setup-tes option is false/not specified
	--tes-role-alias-name, -r	Name of the RoleAlias to attach to the IAM role for TES in the AWS IoT cloud, if the role alias does not exist then it will be created in your AWS account, ignored if --setup-tes option is false/not specified
	--provision, -p 		Y/N Indicate if you want to register the device as an AWS IoT thing, ignored if
                                        --provision option is false/not specified
	--aws-region, -ar		AWS region where the resources should be looked for/created
	--setup-tes, -t 		Y/N Indicate if you want to use Token Exchange Service to talk toAWS services using the
                                        device certificate, ignored if --provision option is false/not specified
	--install-cli, -ic 		Y/N Indicate if you want to install Greengrass device CLI. {}
	--setup-system-service, -ss     Y/N Indicate if you want to setup Greengrass as a system service
```

## Set up steps with Greengrass zip file
This workflow has been implemented for Ubuntu. Use as a reference.
```
# Move aws.greengrass.nucleus.2.0.0.zip to test device
# Set up aws creds
unzip aws.greengrass.nucleus.2.0.0.zip -d GreengrassCore
java -Droot=~/gg_home -Dlog.level=WARN -jar ./GreengrassCore/lib/Greengrass.jar --provision true --aws-region us-east-1 --thing-name <test-device> --setup-tes true -tra <test-role-alias> -ss true

# Verify the setup
tree ~/gg_home/
sudo service greengrass status
sudo journalctl -u greengrass -f
```
