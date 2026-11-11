import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { z } from 'zod';

const tables = ['admins', 'sessions', 'devices', 'device_credentials', 'challenges', 'device_configs', 'config_mutations', 'app_snapshots', 'usage_sessions', 'coverage_gaps', 'releases', 'upgrade_jobs', 'command_receipts', 'notification_outbox', 'usage_clearances', 'auth_attempts'] as const;
export type Table = typeof tables[number];
export function tablesForVersion(version:number): readonly Table[] { return tables.filter(table => (version>=3||table!=='usage_clearances')&&(version>=4||table!=='auth_attempts')); }
// Tables are a closed, internal allowlist. All values use bound parameters.
export class Store {
  readonly db: Database.Database;
  constructor(directory: string) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    this.db = new Database(join(directory, 'manager.sqlite'));
    this.db.pragma('journal_mode = WAL'); this.db.pragma('busy_timeout = 5000');
    const version = this.db.pragma('user_version', { simple: true });
    if (version !== 0 && version !== 1 && version !== 2 && version !== 3 && version !== 4) { this.db.close(); throw new Error('Unsupported database version'); }
    if (version === 1 || version === 2 || version === 3 || version === 4) {
      try {
        for (const table of tablesForVersion(version)) {
          const columns = this.db.prepare(`PRAGMA table_info(${table})`).all().map(row => z.object({ name: z.string() }).parse(row).name);
          if (columns.length !== 3 || !['id', 'deviceId', 'data'].every(name => columns.includes(name))) throw new Error('Invalid database schema');
        }
      } catch (error) { this.db.close(); throw error; }
      if (version === 1) this.migrateUsageIndexes();
      if (version < 3) this.migrateUsageClearances();
      if (version < 4) this.migrateAuthAttempts();
      return;
    }
    const existing = this.db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").all();
    if (existing.length) { this.db.close(); throw new Error('Unversioned database schema'); }
    this.db.transaction(() => {
      for (const table of tables) {
        this.db.exec(`CREATE TABLE IF NOT EXISTS ${table} (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)))`);
        this.db.exec(`CREATE INDEX IF NOT EXISTS ${table}_device ON ${table}(deviceId)`);
      }
      this.db.exec("CREATE UNIQUE INDEX IF NOT EXISTS device_install ON devices(json_extract(data,'$.installId'))");
      this.migrateUsageIndexes();
      this.db.pragma('user_version = 4');
    })();
  }
  private migrateUsageIndexes(): void {
    this.db.transaction(() => {
      this.db.exec("CREATE INDEX usage_time ON usage_sessions(deviceId,COALESCE(json_extract(data,'$.startedAtMs'),json_extract(data,'$.endedAtMs'),json_extract(data,'$.observedAtMs')))");
      this.db.exec("CREATE INDEX gap_time ON coverage_gaps(deviceId,json_extract(data,'$.startedAtMs'))");
      this.db.pragma('user_version = 2');
    })();
  }
  private migrateUsageClearances(): void {
    this.db.transaction(() => {
      this.db.exec("CREATE TABLE usage_clearances (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)))");
      this.db.exec("CREATE INDEX usage_clearances_device ON usage_clearances(deviceId)");
      this.db.pragma('user_version = 3');
    })();
  }
  private migrateAuthAttempts(): void {
    this.db.transaction(() => {
      this.db.exec("CREATE TABLE auth_attempts (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)))");
      this.db.exec("CREATE INDEX auth_attempts_device ON auth_attempts(deviceId)");
      this.db.pragma('user_version = 4');
    })();
  }
  get<T>(table: Table, id: string, schema: z.ZodType<T>): T | null {
    const row = this.db.prepare(`SELECT data FROM ${table} WHERE id=?`).get(id);
    if (!row) return null;
    return this.decode(row, schema);
  }
  private decode<T>(row: unknown, schema: z.ZodType<T>): T {
    try { return schema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown); }
    catch { throw new Error('Stored record is unreadable'); }
  }
  list<T>(table: Table, schema: z.ZodType<T>, deviceId?: string, limit = 200, offset = 0): T[] {
    const statement = deviceId === undefined ? `SELECT data FROM ${table} ORDER BY rowid DESC LIMIT ? OFFSET ?` : `SELECT data FROM ${table} WHERE deviceId=? ORDER BY rowid DESC LIMIT ? OFFSET ?`;
    const params = deviceId === undefined ? [limit, offset] : [deviceId, limit, offset];
    return this.db.prepare(statement).all(...params).map(row => this.decode(row, schema));
  }
  put(table: Table, id: string, deviceId: string, data: unknown): void {
    this.db.prepare(`INSERT INTO ${table}(id,deviceId,data) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data,deviceId=excluded.deviceId`).run(id, deviceId, JSON.stringify(data));
  }
  delete(table: Table, id: string): void { this.db.prepare(`DELETE FROM ${table} WHERE id=?`).run(id); }
  deleteDevice(table: Table, deviceId: string): void { this.db.prepare(`DELETE FROM ${table} WHERE deviceId=?`).run(deviceId); }
  transaction<T>(work: () => T): T { return this.db.transaction(work)(); }
  close(): void { this.db.close(); }
}
