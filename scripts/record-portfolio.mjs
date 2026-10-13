import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function record() {
  const baseURL = new URL(process.env.WEB_BASE_URL ?? 'http://127.0.0.1:18088');
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(baseURL.hostname);
  if (baseURL.username || baseURL.password || baseURL.search || baseURL.hash || baseURL.pathname !== '/'
      || (baseURL.protocol !== 'https:' && !(baseURL.protocol === 'http:' && loopback))) {
    throw new Error('WEB_BASE_URL must be an HTTPS or loopback HTTP origin without credentials, query, or path');
  }
  const lock = JSON.parse(readFileSync(resolve(root, 'web/package-lock.json'), 'utf8'));
  const version = lock.packages['node_modules/@playwright/test'].version;
  const installed = resolve(root, 'web/node_modules/@playwright/test/package.json');
  if (!existsSync(installed) || JSON.parse(readFileSync(installed, 'utf8')).version !== version) {
    throw new Error('Install the locked web dependencies with npm ci in web using Node 22 or later first');
  }
  const captureRoot = resolve(root, 'build/portfolio');
  const output = process.argv[2] ? resolve(root, process.argv[2])
    : resolve(captureRoot, `capture-${new Date().toISOString().replace(/[:.]/g, '-')}`);
  if (process.argv.length > 3 || !output.startsWith(captureRoot + sep) || existsSync(output)) {
    throw new Error('Provide a new output directory under build/portfolio; existing captures are never overwritten');
  }
  const image = `mcr.microsoft.com/playwright:v${version}-noble`;
  const sourceFiles = ['web/e2e/live-ride.spec.ts', 'web/playwright.config.ts', 'web/package-lock.json'];
  const metadata = {
    startedAt: new Date().toISOString(),
    revision: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    dirty: execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }).trim() !== '',
    baseURL: baseURL.origin,
    image,
    browserMemory: '2g',
    sharedMemory: '512m',
    sourceSha256: Object.fromEntries(sourceFiles.map(file => [file,
      createHash('sha256').update(readFileSync(resolve(root, file))).digest('hex')])),
  };
  mkdirSync(output, { recursive: true, mode: 0o700 });
  const manifest = resolve(output, 'capture.json');
  writeFileSync(manifest, JSON.stringify(metadata, null, 2) + '\n', { mode: 0o600 });
  const result = spawnSync('docker', ['run', '--rm', '--network', 'host', '--memory=2g', '--shm-size=512m',
    '-e', `WEB_BASE_URL=${baseURL.origin}`, '-e', 'PORTFOLIO_CAPTURE=1',
    '-v', `${resolve(root, 'web')}:/work:ro`, '-v', `${output}:/artifacts`, '-w', '/work', image,
    'node', 'node_modules/@playwright/test/cli.js', 'test', '--config', 'playwright.config.ts',
    '--output', '/artifacts/test-results', '--reporter=line'], { cwd: root, stdio: 'inherit' });
  writeFileSync(manifest, JSON.stringify({ ...metadata, finishedAt: new Date().toISOString(),
    exitCode: result.status, signal: result.signal }, null, 2) + '\n');
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Recording failed; keep ${output} private and inspect its test results`);
  const run = JSON.parse(readFileSync(resolve(output, 'test-results/.last-run.json'), 'utf8'));
  if (run.status !== 'passed') throw new Error('Recording did not produce a passing test result');
  console.log(`Verified portfolio capture: ${output}`);
}

if (process.argv[2] === '--help' && process.argv.length === 3) {
  console.log('Usage: node scripts/record-portfolio.mjs [new-directory-under-build/portfolio]');
  console.log('Requires an isolated demo stack, locked web dependencies, and Docker host networking.');
  console.log('WEB_BASE_URL defaults to http://127.0.0.1:18088. Videos remain in ignored build output.');
} else {
  try {
    record();
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}