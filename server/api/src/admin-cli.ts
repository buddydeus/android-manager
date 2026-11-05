import { provisionAdmin } from './auth.ts';

try {
  const directory = process.env.AMS_DATA_DIR;
  const username = process.env.AMS_ADMIN_USER;
  const password = process.env.AMS_ADMIN_PASSWORD;
  if (!directory || !username || !password) throw new Error('请提供 AMS_DATA_DIR、AMS_ADMIN_USER、AMS_ADMIN_PASSWORD');
  provisionAdmin(directory, username, password);
  process.stdout.write('管理员已创建\n');
} catch {
  process.stderr.write('管理员初始化失败：请检查参数、数据目录及是否已经初始化\n');
  process.exitCode = 1;
}
