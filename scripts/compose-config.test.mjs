import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { devNull } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const portNames = ['POSTGRES_PORT', 'VALKEY_PORT', 'API_PORT', 'MANAGEMENT_PORT', 'WEB_PORT', 'OSRM_PORT'];
const defaults = ['5434', '6380', '8080', '8081', '8088', '5000'];

function configuration(overrides = {}) {
  const environment = { ...process.env };
  for (const name of [...portNames, 'MAPS_DIR', 'COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_PROJECT_NAME']) {
    delete environment[name];
  }
  return JSON.parse(execFileSync('docker', ['compose', '--env-file', devNull, '-f', 'docker-compose.yml',
    '--profile', 'simulator', '--profile', 'routing', 'config', '--format', 'json'], {
    cwd: root, env: { ...environment, ...overrides }, encoding: 'utf8', timeout: 30_000,
  }));
}

function publishedPorts(config) {
  return [config.services.postgres.ports[0].published, config.services.valkey.ports[0].published,
    ...config.services.app.ports.map(port => port.published), config.services.web.ports[0].published,
    config.services.osrm.ports[0].published];
}

test('default host ports and read-only map locations remain unchanged', () => {
  const config = configuration();
  assert.deepEqual(publishedPorts(config), defaults);
  for (const service of ['web', 'osrm']) {
    assert.equal(config.services[service].volumes[0].source, resolve(root, '.maps'));
    assert.equal(config.services[service].volumes[0].read_only, true);
  }
});

test('isolated overrides do not alter internal application addresses', () => {
  const baseline = configuration();
  const ports = ['15434', '16380', '18080', '18081', '18088', '15000'];
  const fixture = resolve(root, 'web/testdata');
  const config = configuration({
    ...Object.fromEntries(portNames.map((name, index) => [name, ports[index]])), MAPS_DIR: fixture,
  });
  assert.deepEqual(publishedPorts(config), ports);
  assert.deepEqual(config.services.app.environment, baseline.services.app.environment);
  assert.deepEqual(config.services.app.ports.map(port => port.target), [8080, 8081]);
  for (const service of ['web', 'osrm']) {
    assert.equal(config.services[service].volumes[0].source, fixture);
    assert.equal(config.services[service].volumes[0].read_only, true);
  }
});