import { createHash, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, open, rename, unlink, stat } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import multipart from '@fastify/multipart';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ApiError, idSchema } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { requireDevice } from './device.ts';
import { requireAdmin } from './auth.ts';
import { targetDevice } from './admin.ts';
const hashSchema = z.string().regex(/^[a-f0-9]{64}$/);
export const apkSchema = z.object({ packageName: z.literal('com.buddydeus.androidmanager'), versionCode: z.int().positive(), versionName: z.string().max(100), minSdk: z.int().min(1).max(100), signers: z.array(hashSchema).min(1).max(10) }).strict();
export const releaseSchema = apkSchema.extend({ id: hashSchema, sha256: hashSchema, size: z.int().positive().max(100 * 1024 * 1024), createdAtMs: z.number() });
const terminal = ['SUCCEEDED', 'FAILED', 'UNKNOWN', 'SUPERSEDED'];
const state = z.enum(['QUEUED', 'DOWNLOADING', 'VERIFIED', 'INSTALLING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'SUPERSEDED']);
export const jobSchema = z.object({ id: idSchema, release: releaseSchema, state, result: z.string(), createdAtMs: z.number(), receiptVersion: z.int().nonnegative(), receipt: z.unknown().optional() });
const receiptSchema = z.object({ id: idSchema, version: z.int().positive(), state, result: z.string().max(1000), installedVersion: z.int().nonnegative().nullable().optional(), installedSigners: z.array(hashSchema).max(10).optional() }).strict();
const run = promisify(execFile);
export async function inspectApk(file: string, tools: string): Promise<z.infer<typeof apkSchema>> {
  try {
    const opts = { timeout: 30000, maxBuffer: 1024 * 1024, encoding: 'utf8' as const };
    const certificates = await run(join(tools, 'apksigner'), ['verify', '--print-certs', file], opts);
    const badging = await run(join(tools, 'aapt2'), ['dump', 'badging', file], opts);
    const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/m.exec(badging.stdout);
    const sdk = /^(?:sdkVersion|minSdkVersion):'(\d+)'/m.exec(badging.stdout);
    const signers = [...certificates.stdout.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$/gm)].map(x => x[1].toLowerCase()).sort();
    return apkSchema.parse({ packageName: pkg?.[1], versionCode: Number(pkg?.[2]), versionName: pkg?.[3], minSdk: Number(sdk?.[1]), signers: [...new Set(signers)] });
  } catch (error) {
    if (error instanceof Error && 'code' in error && error.code === 'ENOENT') throw new ApiError(503, 'APK_TOOLS_MISSING', '安装包校验工具不可用，请检查服务器部署');
    throw new ApiError(400, 'APK_INVALID', '安装包签名、包名或版本信息校验失败');
  }
}
export async function releaseRoutes(app: FastifyInstance, ctx: Context, directory: string, tools: string): Promise<void> {
  const objects = join(directory, 'releases'); const temporary = join(objects, '.tmp');
  await mkdir(temporary, { recursive: true, mode: 0o700 });
  await app.register(multipart, { limits: { files: 1, fields: 0, parts: 1, fileSize: 100 * 1024 * 1024 } });
  app.post('/api/v1/admin/releases', async request => {
    requireAdmin(ctx, request, true);
    const tmp = join(temporary, `${randomUUID()}.apk`); let size = 0; const hash = createHash('sha256'); let count = 0;
    try {
      for await (const part of request.parts()) {
        if (part.type !== 'file' || ++count > 1) throw new ApiError(400, 'ONE_APK_REQUIRED', '请选择一个APK文件');
        await pipeline(part.file, new Transform({ transform(chunk: Buffer, _encoding, callback) { size += chunk.length; hash.update(chunk); callback(null, chunk); } }), createWriteStream(tmp, { flags: 'wx', mode: 0o600 }));
        if (part.file.truncated) throw new ApiError(413, 'APK_TOO_LARGE', '安装包超过100MiB限制');
      }
      if (count !== 1 || size < 4) throw new ApiError(400, 'APK_INVALID', '安装包文件无效');
      const handle = await open(tmp, 'r'); const prefix = Buffer.alloc(4);
      try { await handle.read(prefix, 0, 4, 0); } finally { await handle.close(); }
      if (!prefix.equals(Buffer.from([0x50, 0x4b, 0x03, 0x04]))) throw new ApiError(400, 'APK_INVALID', '安装包文件无效');
      const metadata = await inspectApk(tmp, tools); const sha256 = hash.digest('hex');
      const existing = ctx.store.get('releases', sha256, releaseSchema);
      await rename(tmp, join(objects, `${sha256}.apk`));
      if (existing) return { data: existing };
      const release = releaseSchema.parse({ ...metadata, id: sha256, sha256, size, createdAtMs: ctx.now() });
      ctx.store.put('releases', sha256, '', release); return { data: release };
    } finally { await unlink(tmp).catch(error => { if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error; }); }
  });
  app.get('/api/v1/admin/releases', request => {
    requireAdmin(ctx, request); const query = z.object({ page: z.coerce.number().int().min(1).max(100000).default(1) }).strict().parse(request.query);
    return { data: ctx.store.list('releases', releaseSchema, undefined, 50, (query.page - 1) * 50), page: query.page };
  });
  app.post('/api/v1/admin/devices/:id/upgrades', request => {
    requireAdmin(ctx, request, true); const device = targetDevice(ctx, request);
    const input = z.object({ jobId: idSchema, releaseId: hashSchema }).strict().parse(request.body);
    const key = `${device.id}:${input.jobId}`; const previous = ctx.store.get('upgrade_jobs', key, jobSchema);
    if (previous) { if (previous.release.id !== input.releaseId) throw new ApiError(409, 'JOB_REUSE', '升级任务编号内容不一致'); return { data: previous }; }
    const release = ctx.store.get('releases', input.releaseId, releaseSchema);
    if (!release) throw new ApiError(404, 'RELEASE_NOT_FOUND', '安装包版本不存在');
    if (release.versionCode <= device.versionCode || release.minSdk > device.apiLevel) throw new ApiError(409, 'RELEASE_INCOMPATIBLE', '版本不高于设备版本，或不支持设备系统');
    const job = { id: input.jobId, release, state: 'QUEUED' as const, result: '等待设备领取', createdAtMs: ctx.now(), receiptVersion: 0 };
    ctx.store.put('upgrade_jobs', key, device.id, job); ctx.notify(device.id, { type: 'upgrade.changed' });return { data: job };
  });
  app.get('/api/v1/device/upgrades', request => {
    const device = requireDevice(ctx, request);
    const rows = ctx.store.db.prepare("SELECT data FROM upgrade_jobs WHERE deviceId=? AND json_extract(data,'$.state') NOT IN ('SUCCEEDED','FAILED','UNKNOWN','SUPERSEDED') ORDER BY rowid LIMIT 20").all(device.id);
    return { data: { jobs: rows.map(row => jobSchema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown)) } };
  });
  app.get('/api/v1/admin/devices/:id/upgrades', request => {
    requireAdmin(ctx, request); const device = targetDevice(ctx, request);
    const query = z.object({ page: z.coerce.number().int().min(1).max(100000).default(1) }).strict().parse(request.query);
    return { data: ctx.store.list('upgrade_jobs', jobSchema, device.id, 50, (query.page - 1) * 50), page: query.page };
  });
  app.post('/api/v1/device/upgrades/receipts', request => {
    const device = requireDevice(ctx, request); const rows = z.object({ receipts: z.array(receiptSchema).max(200) }).strict().parse(request.body).receipts;
    const accepted: { id: string; version: number }[] = [];const orphaned: { id: string; version: number }[] = [];
    ctx.store.transaction(() => { for (const receipt of rows) {
      const key = `${device.id}:${receipt.id}`; const job = ctx.store.get('upgrade_jobs', key, jobSchema);
      if (!job) { orphaned.push({ id: receipt.id, version: receipt.version });continue; }
      if (receipt.state === 'SUCCEEDED' && (receipt.installedVersion !== job.release.versionCode || JSON.stringify([...receipt.installedSigners ?? []].sort()) !== JSON.stringify(job.release.signers))) throw new ApiError(400, 'INSTALLED_EVIDENCE_REQUIRED', '实际安装版本或签名与目标不符');
      if (receipt.version === job.receiptVersion && JSON.stringify(receipt) !== JSON.stringify(job.receipt)) throw new ApiError(409, 'RECEIPT_REUSE', '回执版本内容不一致');
      if (receipt.version > job.receiptVersion) {
        if (terminal.includes(job.state) && receipt.state !== job.state) throw new ApiError(409, 'JOB_TERMINAL', '升级任务已结束');
        ctx.store.put('upgrade_jobs', key, device.id, { ...job, state: receipt.state, result: receipt.result, receiptVersion: receipt.version, receipt });
      }
      accepted.push({ id: receipt.id, version: receipt.version });
    } });
    return { data: { accepted, orphaned } };
  });
  app.get('/api/v1/device/releases/:id/apk', async (request, reply) => {
    const device = requireDevice(ctx, request); const { id } = z.object({ id: hashSchema }).parse(request.params);
    const assignment = ctx.store.db.prepare("SELECT 1 FROM upgrade_jobs WHERE deviceId=? AND json_extract(data,'$.release.id')=? LIMIT 1").get(device.id, id);
    if (!assignment) throw new ApiError(403, 'RELEASE_NOT_ASSIGNED', '该安装包未分配给此设备');
    const release = ctx.store.get('releases', id, releaseSchema); if (!release) throw new ApiError(404, 'RELEASE_NOT_FOUND', '安装包不存在');
    const file = join(objects, `${id}.apk`);
    try { if ((await stat(file)).size !== release.size) throw new Error('size mismatch'); }
    catch { throw new ApiError(503, 'RELEASE_UNAVAILABLE', '安装包文件不可用，请检查服务器存储'); }
    return reply.type('application/vnd.android.package-archive').header('Content-Length', release.size).send(createReadStream(file));
  });
}
