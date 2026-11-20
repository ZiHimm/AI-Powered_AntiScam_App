// Copies face-api.js + model weights from the repo's vendor/ into www/vendor
// so both the relay (browser testing) and the Android app can serve them.
import { cpSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, '..', 'vendor');
const dst = join(root, 'www', 'vendor');
if (!existsSync(src)) {
  console.error(`vendor folder not found at ${src}`);
  process.exit(1);
}
cpSync(src, dst, { recursive: true, filter: (p) => !p.endsWith('.DS_Store') });
console.log(`copied vendor/ -> www/vendor`);
