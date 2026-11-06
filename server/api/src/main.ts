import { createApp } from './app.ts';
import { join } from 'node:path';

try {
  const directory = process.env.AMS_DATA_DIR;
  const enrollmentKey = process.env.AMS_ENROLLMENT_KEY;
  const port = Number(process.env.PORT ?? '8080');
  if (!directory || !enrollmentKey || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid deployment configuration');
  const app = await createApp({ directory, enrollmentKey, origin: process.env.AMS_ORIGIN ?? 'https://ams.opt.work', webRoot: join(import.meta.dirname, '../../web'), trustProxy: process.env.AMS_TRUST_PROXY?.split(',').filter(Boolean) });
  for (const signal of ['SIGTERM', 'SIGINT'] as const) process.once(signal, () => { void app.close(); });
  await app.listen({ port, host: '0.0.0.0' });
  process.stdout.write(`AMS 服务已启动，端口 ${port}\n`);
} catch {
  process.stderr.write('AMS 服务启动失败，请检查部署配置及数据目录\n');
  process.exitCode = 1;
}
