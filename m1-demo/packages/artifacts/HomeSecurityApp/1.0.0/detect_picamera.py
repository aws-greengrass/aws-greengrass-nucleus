import basic_detect_picamera

useStreamManager = False # TO update to true in part 3

if not useStreamManager:
  basic_detect_picamera.main()

else:
  from streamManagerHelper import StreamUploader
  import json
  # part 3 & 4
  streamUploader = StreamUploader()
  streamUploader.createStreamWithIotAnalyticsExport("m1demoimagedata")
  streamUploader.createStreamWithKinesisExport("RpiImageClassificationStream")

  def getDeviceThingName():
    # Extract serial from cpuinfo file
    cpuserial = "0000000000000000"
    try:
      f = open('/proc/cpuinfo','r')
      for line in f:
        if line[0:6]=='Serial':
          cpuserial = line[10:26]
      f.close()
    except:
      cpuserial = "ERROR000000000"

    if cpuserial == '100000008793372b':
      return "EthanTestPi_1"
    elif cpuserial == '1000000083da38ba':
      return "EthanTestPi_2"
    else:
      return "Unknown_device"

  def push_person_possibility_to_cloud(results, labels):
    deviceThingName = getDeviceThingName()
    millis = int(round(time.time() * 1000))

    maxScore = 0

    for obj in results:
      label = labels[obj['class_id']]
      if (label == 'person'):
        if (obj['score'] > maxScore):
          maxScore = obj['score']

    if (maxScore > 0):
      print(f"Detected person appearance with possibility: [{str(maxScore)}] at timestamp: [{str(millis)}]")

      # part 3 new change
      measurement = {
          "TimeOfCapture": millis,
          "Possibility": float(maxScore),
          "DeviceId": deviceThingName
      }
      data = json.dumps(measurement).encode()
      print("Pushing it to stream manager")
      streamUploader.appendToStream("m1demoimagedata", data)
      streamUploader.appendToStream("RpiImageClassificationStream", data)


  basic_detect_picamera.publish_camera_data_function = push_person_possibility_to_cloud
  basic_detect_picamera.main()
