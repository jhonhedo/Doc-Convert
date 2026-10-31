const http = require('http');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

const staticDir = path.join(__dirname, '..', 'src', 'main', 'resources', 'static');
const mimeTypes = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.png': 'image/png'
};

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/') reqPath = '/index.html';
  if (!path.extname(reqPath)) reqPath += '.html';
  const filePath = path.join(staticDir, reqPath);
  if (fs.existsSync(filePath)) {
    const ext = path.extname(filePath);
    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'text/plain' });
    fs.createReadStream(filePath).pipe(res);
  } else {
    res.writeHead(404);
    res.end('Not Found');
  }
});

function runCmd(cmd) {
  return new Promise((resolve, reject) => {
    exec(cmd, (err, stdout, stderr) => {
      if (err) reject(err);
      else resolve(stdout);
    });
  });
}

server.listen(8089, async () => {
  console.log('Static server listening on 8089');
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const targetDir = path.join(__dirname, '..', 'target', 'screenshots');
  const tempProfile = path.join(__dirname, '..', 'target', 'chrome-profile');
  if (!fs.existsSync(targetDir)) {
    fs.mkdirSync(targetDir, { recursive: true });
  }

  const viewports = [
    { w: 375, h: 812, name: 'mobile_375px' },
    { w: 768, h: 1024, name: 'tablet_768px' },
    { w: 1280, h: 800, name: 'desktop_1280px' }
  ];

  try {
    for (const vp of viewports) {
      const outFile = path.join(targetDir, `step4_${vp.name}.png`);
      const cmd = `"${chromePath}" --headless=new --no-sandbox --disable-gpu --user-data-dir="${tempProfile}" --window-size=${vp.w},${vp.h} --screenshot="${outFile}" http://127.0.0.1:8089/index.html`;
      console.log(`Taking screenshot: ${vp.name} (${vp.w}x${vp.h})...`);
      await runCmd(cmd);
      console.log(`Saved: ${outFile}`);
    }
    console.log('All responsive screenshots captured successfully.');
  } catch (err) {
    console.error('Error taking screenshots:', err);
  } finally {
    server.close();
    process.exit(0);
  }
});
