// Copies the web app into www/, which Capacitor packages into the Android app.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const out = path.join(root, 'www');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out);
for (const f of ['index.html', 'app.js', 'styles.css', 'vendor']) {
  fs.cpSync(path.join(root, f), path.join(out, f), { recursive: true });
}
console.log('www/ ready');
