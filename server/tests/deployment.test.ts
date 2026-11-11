import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, access, writeFile, rm, cp, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const root = join(import.meta.dirname, '..');
test('build:server produces a self-contained source bundle and refuses to overwrite deployed secrets', async t => {
  const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  assert.equal(typeof pkg.scripts['build:server'], 'string');
  execFileSync(process.execPath, ['scripts/build-server.mjs'], { cwd: root });
  const out = join(root, 'out');
  for (const name of ['deploy.sh', 'compose.yaml', 'Dockerfile', 'package-lock.json', 'api/src/main.ts', 'web/src/App.vue', 'contracts/index.ts', 'README.md']) await access(join(out, name));
  await assert.rejects(access(join(out, 'node_modules')));await assert.rejects(access(join(out, 'tests')));
  execFileSync('bash', ['-n', join(out, 'deploy.sh')]);
  const sentinel = join(out, '.env');await writeFile(sentinel, 'PRIVATE=keep\n');
  try { assert.notEqual(spawnSync(process.execPath, ['scripts/build-server.mjs'], { cwd: root }).status, 0);assert.equal(await readFile(sentinel, 'utf8'), 'PRIVATE=keep\n'); }
  finally { await rm(sentinel); }
  const nested = join(out, 'web', 'local-backup.json');await writeFile(nested, 'keep');
  try { assert.notEqual(spawnSync(process.execPath, ['scripts/build-server.mjs'], { cwd: root }).status, 0);assert.equal(await readFile(nested, 'utf8'), 'keep'); }
  finally { await rm(nested, { force: true }); }
});
test('deploy script initializes once, preserves credentials on rerun and stops on build failure', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ams-deploy-test-'));t.after(() => rm(dir, { recursive: true, force: true }));
  await cp(join(root, 'deploy/deploy.sh'), join(dir, 'deploy.sh'));
  const bin = join(dir, 'docker');const log = join(dir, 'calls');const state = join(dir, 'initialized');
  await writeFile(bin, `#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$CALL_LOG"
case "$*" in
  *'build ams'*) [[ -z \${AMS_ENROLLMENT_KEY+x} ]]; [[ "\${FAIL_BUILD:-}" != 1 ]] ;;
  *'deployment-cli.js'*) if [[ -f "$STATE_FILE" ]]; then echo initialized; else echo uninitialized; fi ;;
  *'admin-cli.js'*) [[ -n "$AMS_ADMIN_USER" ]]; touch "$STATE_FILE" ;;
esac
`);await chmod(bin, 0o755);
  const env = { ...process.env, PATH: dir + ':' + process.env.PATH, CALL_LOG: log, STATE_FILE: state, AMS_ENROLLMENT_KEY: 'a'.repeat(32), AMS_ADMIN_USER: 'operator' };
  const run = (extra = {}) => spawnSync('bash', ['deploy.sh'], { cwd: dir, env: { ...env, ...extra }, encoding: 'utf8' });
  const first = run();assert.equal(first.status, 0, first.stderr + first.stdout);const secret = await readFile(join(dir, '.env'), 'utf8');assert.equal(run({ AMS_ENROLLMENT_KEY: 'b'.repeat(32) }).status, 0);assert.equal(await readFile(join(dir, '.env'), 'utf8'), secret);
  let calls = await readFile(log, 'utf8');assert.equal(calls.split('admin-cli.js').length - 1, 1);assert(!calls.includes('down'));
  await writeFile(log, '');assert.notEqual(run({ FAIL_BUILD: '1' }).status, 0);calls = await readFile(log, 'utf8');assert(!calls.includes('up -d'));assert(!calls.includes('admin-cli'));
});
test('deployment state check distinguishes first install, existing admin and damaged database', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ams-deploy-db-'));t.after(() => rm(dir, { recursive: true, force: true }));
  const run = () => spawnSync(process.execPath, ['api/src/deployment-cli.ts'], { cwd: root, env: { ...process.env, AMS_DATA_DIR: dir }, encoding: 'utf8' });
  assert.equal(run().stdout.trim(), 'uninitialized');
  const { provisionAdmin } = await import('../api/src/auth.ts');provisionAdmin(dir, 'operator', 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ');assert.equal(run().stdout.trim(), 'initialized');
  const { Store } = await import('../api/src/db.ts');const store = new Store(dir);store.db.pragma('user_version=999');store.close();assert.notEqual(run().status, 0);
  const { default: Database } = await import('better-sqlite3');const retained = new Database(join(dir, 'manager.sqlite'), { readonly: true });
  try { assert.equal(retained.pragma('user_version', { simple: true }), 999);assert.deepEqual(retained.prepare('SELECT count(*) AS n FROM admins').get(), { n: 1 }); }finally { retained.close(); }
});

test('legacy deployment probe remains read-only and leaves v3 restartable', async t => {
 const dir=await mkdtemp(join(tmpdir(),'ams-legacy-probe-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const {Store}=await import('../api/src/db.ts');const store=new Store(dir);store.put('admins','administrator','',{username:'operator',kdf:'scrypt',kdfVersion:1});store.db.exec('DROP TABLE auth_attempts');store.db.pragma('user_version=3');store.close();
 const result=spawnSync(process.execPath,['api/src/deployment-cli.ts'],{cwd:root,env:{...process.env,AMS_DATA_DIR:dir},encoding:'utf8'});assert.equal(result.status,0,result.stderr);assert.equal(result.stdout.trim(),'legacy');
 const {default:Database}=await import('better-sqlite3');const db=new Database(join(dir,'manager.sqlite'),{readonly:true});try{assert.equal(db.pragma('user_version',{simple:true}),3);assert.equal(db.prepare("SELECT 1 FROM sqlite_master WHERE name='auth_attempts'").get(),undefined);}finally{db.close();}
});
