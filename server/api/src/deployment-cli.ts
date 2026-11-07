// Administrator existence check; Store creates new databases and validates/migrates known schemas.
import { Store } from './db.ts';
try {
  const directory = process.env.AMS_DATA_DIR;
  if (!directory) throw new Error('Missing data directory');
  const store = new Store(directory);
  try { process.stdout.write(store.db.prepare("SELECT 1 FROM admins WHERE id='administrator'").get() ? 'initialized\n' : 'uninitialized\n'); }
  finally { store.close(); }
} catch { process.stderr.write('部署检查失败：请检查数据目录和存储版本，禁止清库重试\n');process.exitCode = 1; }
