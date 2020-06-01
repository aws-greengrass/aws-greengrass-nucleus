#!/usr/bin/env bash
# Prerequisites:
# - AWS Credentials are correctly set up and can be used in console
#   (if default region is set up, we can remove all `--region us-east-1`)
# - Three Raspberry Pis have been bootstrapped with EasySetup script. Keep IoT thing names handy.

# 1. Create an IoT thing group and add two IoT things
aws --region us-east-1 iot create-thing-group --thing-group-name MySmartFarm
aws --region us-east-1 iot add-thing-to-thing-group --thing-group-name MySmartFarm --thing-name <rpi1>
aws --region us-east-1 iot add-thing-to-thing-group --thing-group-name MySmartFarm --thing-name <rpi2>
aws --region us-east-1 iot list-things-in-thing-group --thing-group-name MySmartFarm

# 2. Publish MyFarmingApp to Component Management Service
aws --endpoint-url https://3w5ajog718.execute-api.us-east-1.amazonaws.com/Beta/ --region us-east-1 evergreencomponent \
  create-component --recipe file://MyFarmingApp.yaml
aws --endpoint-url https://3w5ajog718.execute-api.us-east-1.amazonaws.com/Beta --region us-east-1 evergreencomponent \
  create-component-artifact-upload-url --component-name MyFarmingApp --component-version 1.0.0 \
  --artifact-name MyFarmingAppArtifact.zip
# Note the presigned url
curl "put the presigned url here in quotes" --upload-file MyFarmingAppArtifact.zip
aws --endpoint-url https://3w5ajog718.execute-api.us-east-1.amazonaws.com/Beta/ --region us-east-1 evergreencomponent \
  commit-component --component-name MyFarmingApp --component-version 1.0.0

# 3. Deploy in Fleet Configuration Service
aws --endpoint-url https://aqzw8qdn5l.execute-api.us-east-1.amazonaws.com/Beta/ --region us-east-1 evergreenfleet \
  set-configuration --cli-input-json file://setConfig.json
# Note the revision ID
aws --endpoint-url https://aqzw8qdn5l.execute-api.us-east-1.amazonaws.com/Beta/ --region us-east-1 evergreenfleet \
  publish-configuration --target-type thinggroup --target-name MySmartFarm --revision-id 1
# Note the version ID
# (Optional) Get published config
aws --endpoint-url https://aqzw8qdn5l.execute-api.us-east-1.amazonaws.com/Beta/ --region us-east-1 evergreenfleet \
  get-published-configuration --target-type thinggroup --target-name MySmartFarm --version-id 1

# Show IoT jobs in console
# Show deployments happen on RRis

# 4. Add a new device to the IoT Thing Group
aws --region us-east-1 iot add-thing-to-thing-group --thing-group-name MySmartFarm --thing-name <rpi3>
# Show IoT jobs in console
# Show deployments happen on the new RRi
