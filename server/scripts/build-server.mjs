import { cp, lstat, readdir, mkdtemp, readFile, writeFile, rename, rm, chmod } from 'node:fs/promises';
import { join, dirname, basename, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = dirname(dirname(fileURLToPath(import.meta.url)));
const output = join(root, 'out');
const files = ['Dockerfile', '.dockerignore', 'compose.yaml', 'Caddyfile', 'package.json', 'package-lock.json', 'tsconfig.json', 'tsconfig.api.json', 'api', 'contracts', 'web'];
async function exists(path) { try { return await lstat(path); } catch (e) { if (e.code === 'ENOENT') return null;throw e; } }
async function sourceFilter(path) {
  const name = basename(path);
  if (name.startsWith('.') || ['node_modules', 'dist', 'out'].includes(name)) return false;
  const stat = await lstat(path);
  if (stat.isSymbolicLink()) throw new Error(`不支持链接：${path}`);
  if (stat.isDirectory()) return true;
  return stat.isFile() && (['.ts', '.vue', '.css', '.html', '.svg'].includes(extname(name)) || name === 'tsconfig.json');
}
async function build() {
  const prior = await exists(output);
  if (prior) {
    if (!prior.isDirectory() || prior.isSymbolicLink()) throw new Error('out必须是生成目录，不能是链接');
    const marker = JSON.parse(await readFile(join(output, 'bundle.json'), 'utf8'));
    if (marker.format !== 'ams-source-bundle-v1' || !Array.isArray(marker.files) || (await readdir(output, { recursive: true })).some(name => name !== 'bundle.json' && !marker.files.includes(name))) throw new Error('out含部署配置或非生成文件；请保留原目录后再构建');
  }
  const stage = await mkdtemp(join(root, '.out-build-'));
  let backup;
  try {
    for (const file of files) {
      const source = join(root, file);const stat = await lstat(source);
      if (stat.isSymbolicLink()) throw new Error(`不支持链接：${source}`);
      await cp(source, join(stage, file), { recursive: true, ...(stat.isDirectory() ? { filter: sourceFilter } : {}) });
    }
    await cp(join(root, 'deploy/deploy.sh'), join(stage, 'deploy.sh'));await chmod(join(stage, 'deploy.sh'), 0o755);
    await cp(join(root, 'deploy/README.md'), join(stage, 'README.md'));
    await writeFile(join(stage, 'bundle.json'), JSON.stringify({ format: 'ams-source-bundle-v1', files: (await readdir(stage, { recursive: true })).sort() }) + '\n');
    if (prior) { backup = await mkdtemp(join(root, '.out-old-'));await rename(output, join(backup, 'out')); }
    try { await rename(stage, output); } catch (e) { if (backup) await rename(join(backup, 'out'), output);throw e; }
    if (backup) await rm(backup, { recursive: true });
    process.stdout.write('已生成 server/out；复制整个目录到服务器后运行 ./deploy.sh\n');
  } finally { await rm(stage, { recursive: true, force: true }); }
}
build().catch(error => { process.stderr.write(`部署包构建失败：${error.message}\n`);process.exitCode = 1; });
