#!/bin/bash

echo "Starting Evergreen Kernel..."

HOME_DIR=/home/pi/.evergreen
THING_NAME=EthanTestPi_1
java -Dlog.store=FILE -Dlog.storeName=${HOME_DIR}/evergreen.log -Droot=${HOME_DIR} -cp EvergreenWithHttpServer.jar com.aws.iot.evergreen.easysetup.EvergreenSetup -p true -ar us-east-1 -t true -tra abanthiyTestRoleAlias -i onlyMain.yaml -tn ${THING_NAME} > /dev/null 2>&1 &

sleep 20

echo "Evergreen Kernel has started successfully."
echo "root dir: "${HOME_DIR}
echo "log path: "${HOME_DIR}/evergreen.log
echo "thing name: "${THING_NAME}
echo "Please go to localhost:1441 to view the local dashboard".
