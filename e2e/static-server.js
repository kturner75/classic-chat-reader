const fs = require('fs');
const http = require('http');
const path = require('path');

const port = Number.parseInt(process.argv[2] || '4173', 10);
const rootDir = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'static');

const mimeTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8'
};

function safeResolve(targetPath) {
  const resolved = path.resolve(rootDir, targetPath);
  if (!resolved.startsWith(rootDir)) {
    return null;
  }
  return resolved;
}

function send(res, statusCode, body, contentType) {
  res.statusCode = statusCode;
  if (contentType) {
    res.setHeader('Content-Type', contentType);
  }
  res.end(body);
}

const server = http.createServer((req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`);
  let pathname = requestUrl.pathname;

  if (pathname === '/') {
    pathname = '/index.html';
  } else if (pathname === '/my-chats') {
    pathname = '/my-chats.html';
  }

  const resolvedPath = safeResolve(pathname.slice(1));
  if (!resolvedPath) {
    send(res, 403, 'Forbidden', 'text/plain; charset=utf-8');
    return;
  }

  fs.stat(resolvedPath, (statErr, stat) => {
    if (statErr || !stat.isFile()) {
      send(res, 404, 'Not Found', 'text/plain; charset=utf-8');
      return;
    }

    const extension = path.extname(resolvedPath).toLowerCase();
    const contentType = mimeTypes[extension] || 'application/octet-stream';
    const stream = fs.createReadStream(resolvedPath);
    res.statusCode = 200;
    res.setHeader('Content-Type', contentType);
    stream.pipe(res);
    stream.on('error', () => {
      if (!res.headersSent) {
        send(res, 500, 'Internal Server Error', 'text/plain; charset=utf-8');
      } else {
        res.destroy();
      }
    });
  });
});

server.listen(port, '127.0.0.1', () => {
  // eslint-disable-next-line no-console
  console.log(`Static test server listening on http://127.0.0.1:${port}`);
});

