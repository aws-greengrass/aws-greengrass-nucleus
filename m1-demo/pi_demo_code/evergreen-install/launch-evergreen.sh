#!/bin/bash

echo "Starting Evergreen Kernel..."

java -Dlog.store=FILE -Dlog.storeName=/home/pi/.evergreen/evergreen.log -Droot=~/.evergreen -cp EvergreenWithHttpServer.jar com.aws.iot.evergreen.easysetup.EvergreenSetup -tn EthanTestPi_1 -p true -ar us-east-1 -t true -tra abanthiyTestRoleAlias  -i onlyMain.yaml > /dev/null 2>&1 &  

sleep 15

echo "Evergreen Kernel has started successfully. Please go to localhost:1441 to view the local dashboard".
