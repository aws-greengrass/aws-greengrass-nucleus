# Part 2 - Object detection App
* name detect_picamera_part2.py to detect_picamera.py
* modify HomeSecurityApp.yaml to use the `# Part2` config
* push to local package repository

# Part 3 - import streamManager as new dependency
* name detect_picamera_part3.py to detect_picamera.py
* modify HomeSecurityApp.yaml to use the `# Part3` config
* push Streammanager component to Rpi 
* push to local package repository on Rpi

# Part 5.prepare - add SafeToUpdate check in yaml
* modify HomeSecurityApp.yaml to use the `# Part5` config
* push to local package repository on Rpi

# Part 4 - publish to cloud
* modify yaml version to 1.1.0
* use greengrass-cli to push to cloud with an updated version
* Deploy to a second Rpi

# Part 5 - add SafeToUpdate check
* set device offline first
* create deployment from cloud to update a package parameter 
* set to not safe-to-update for one of the rPi
* shows that the non-safe-to-update Rpi doesn't update, while the other one updates. Verify parameter updated.
* set the device safe-to-update
* shows that both device are updated