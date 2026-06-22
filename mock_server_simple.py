#!/usr/bin/env python3
"""
Simple HTTP mock server using only standard library.
Runs on port 8081.
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        
        response = {
            "message": "Request successful",
            "path": self.path,
            "status": "ok"
        }
        self.wfile.write(json.dumps(response).encode())
    
    def do_POST(self):
        self.do_GET()
    
    def do_PUT(self):
        self.do_GET()
    
    def do_DELETE(self):
        self.do_GET()
    
    def log_message(self, format, *args):
        # Suppress default logging, or customize it
        print(f"[{self.address_string()}] {args[0]}")

if __name__ == '__main__':
    port = 8081
    server = HTTPServer(('localhost', port), MockHandler)
    print(f"Starting mock server on http://localhost:{port}")
    print("Press Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down mock server...")
        server.shutdown()