from http.server import BaseHTTPRequestHandler, HTTPServer
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

while True:
    time.sleep(10)
