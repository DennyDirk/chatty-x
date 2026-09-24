import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const demo = process.argv.includes('--demo');
const content = [
  `DB_PASSWORD=${randomBytes(24).toString('hex')}`,
  `MASTER_KEY=${randomBytes(32).toString('base64')}`,
  `BOOTSTRAP_TOKEN=${randomBytes(24).toString('hex')}`,
  `BACKEND_TARGET=${demo ? 'demo' : 'production'}`,
  `CHATTY_MODE=${demo ? 'demo' : 'production'}`,
  `COOKIE_SECURE=${!demo}`,
  `SITE_ADDRESS=${demo ? ':80' : 'localhost'}`,
  'HTTP_BIND=127.0.0.1:8088',
  'HTTPS_BIND=127.0.0.1:8443',
  'TELEGRAM_API_ID=0',
  'TELEGRAM_API_HASH=',
  '',
].join('\n');
try {
  writeFileSync(fileURLToPath(new URL('../.env', import.meta.url)), content, { flag: 'wx', mode: 0o600 });
  console.log('.env created. Open it locally to obtain BOOTSTRAP_TOKEN; keep it private.');
} catch (error) {
  if (error.code === 'EEXIST') console.log('.env already exists; no secrets were changed.');
  else throw error;
}
