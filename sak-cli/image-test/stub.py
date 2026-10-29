# the endpoint of sakctl as the image test sees it: every post as its authorization and body, one line
import http.server


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        print(self.headers["Authorization"], body, flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
