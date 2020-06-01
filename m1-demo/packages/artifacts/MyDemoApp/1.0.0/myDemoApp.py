from http.server import BaseHTTPRequestHandler, HTTPServer
import json

safeToUpdate = False

PORT = 8080

class CustomHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/safeToUpdate":
            global safeToUpdate
            if (safeToUpdate):
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
        if self.path == "/safeToUpdate":
            global safeToUpdate
            value = self.headers.get('safeToUpdate')
            if value != None:
                if str(value).lower() == "true":
                    print("Set to true")
                    safeToUpdate = True
                    print(safeToUpdate)
                elif str(value).lower() == "false":
                    print("Set to false")
                    safeToUpdate = False
                    print(safeToUpdate)
            self.send_response(200)

server = HTTPServer(('', PORT), CustomHandler)
print("serving at port", PORT)
server.serve_forever()
