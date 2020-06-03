from http.server import BaseHTTPRequestHandler, HTTPServer
from streamManagerHelper import StreamUploader
import json
import time
import json
import threading
import time
import argparse

parser = argparse.ArgumentParser(description='Process some integers.')
parser.add_argument('--color', dest='color',
                    default='red', help='color')

args = parser.parse_args()
print("parameter color:", args.color, flush=True)

# TODO: start object detection process

# TODO: replace the hook with actual object detection process and camera
cameraInUse = True

def safeToUpdate():
    return not cameraInUse

def pause():
    global cameraInUse
    cameraInUse = False

def resume():
    global cameraInUse
    cameraInUse = True

# HTTP server to handle pause/resume/safeToUpdate check
PORT = 8080
class CustomHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/safeToUpdate":
            global safeToUpdate
            if (safeToUpdate()):
                self.send_response(200)
                self.send_header("Content-type", "text/plain")
                self.end_headers()
                self.wfile.write(bytes("true", "utf-8"))
            else:
                self.send_response(400)
                self.send_header("Content-type", "text/plain")
                self.end_headers()
                self.wfile.write(bytes("false", "utf-8"))

    def do_POST(self):
        if self.path == "/pause":
            pause()
        elif self.path == "/resume":
            resume()
        self.send_response(200)

server = HTTPServer(('', PORT), CustomHandler)

print("serving at port", PORT, flush=True)

httpServerThread = threading.Thread(target=server.serve_forever, args=())
httpServerThread.daemon = True
httpServerThread.start()

#Wait for SM to start listening. Need to add another mechanism to identify SM start up
time.sleep(2)
streamUploader = StreamUploader()
#Create the stream and the Iot Analytics channel in cloud
streamUploader.createStreamWithKinesisExport("RpiImageClassificationStream")
# streamUploader.createStreamWithIotAnalyticsExport("m1demoimagedata")

#Sample measurement. Get this from the camera
measurement = {
    "TimeOfCapture": 1590960712514,
    "Possibility": 0.6875,
    "DeviceId":"Rpi1"
}
data = json.dumps(measurement).encode()
print("Pushing data to stream")

while True:
    #Get the data from camera here
    streamUploader.appendToStream("RpiImageClassificationStream", data)
    # streamUploader.appendToStream("m1demoimagedata", data)
    time.sleep(10)

