import Database from 'better-sqlite3';
import { access, mkdir, mkdtemp, chmod, copyFile, writeFile, rm } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import { releaseSchema } from './releases.ts';
export async function backupData(directory: string, destination: string): Promise<string> {
  const file = join(directory, 'manager.sqlite');await access(file);
  await mkdir(destination, { recursive: true, mode: 0o700 });const target = await mkdtemp(join(destination, 'ams-backup-'));
  try {
    const source = new Database(file, { readonly: true, fileMustExist: true });
    let version: unknown;
    try { version = source.pragma('user_version', { simple: true });if (version !== 1 && version !== 2 && version !== 3) throw new Error('Unsupported backup schema');await source.backup(join(target, 'manager.sqlite')); }
    finally { source.close(); }
    await chmod(join(target, 'manager.sqlite'), 0o600);await mkdir(join(target, 'releases'), { mode: 0o700 });
    const snapshot = new Database(join(target, 'manager.sqlite'), { readonly: true, fileMustExist: true });
    try {
      if (snapshot.pragma('integrity_check', { simple: true }) !== 'ok') throw new Error('Backup integrity check failed');
      for (const row of snapshot.prepare('SELECT data FROM releases').iterate()) {
        const release = releaseSchema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown);
        const output = join(target, 'releases', `${release.id}.apk`);await copyFile(join(directory, 'releases', `${release.id}.apk`), output);await chmod(output, 0o600);
        const digest = createHash('sha256');let size = 0;
        for await (const chunk of createReadStream(output)) { const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);digest.update(bytes);size += bytes.length; }
        if (size !== release.size || digest.digest('hex') !== release.sha256) throw new Error('Backup APK integrity check failed');
      }
    } finally { snapshot.close(); }
    await writeFile(join(target, 'backup.json'), JSON.stringify({ formatVersion: 1, databaseVersion: version, savedAtMs: Date.now() }), { mode: 0o600 });return target;
  } catch (error) { await rm(target, { recursive: true, force: true });throw error; }
}
