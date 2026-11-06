import { backupData } from './backup.ts';
try {
  const directory = process.env.AMS_DATA_DIR;const destination = process.env.AMS_BACKUP_DIR;
  if (!directory || !destination) throw new Error('Missing backup configuration');
  const path = await backupData(directory, destination);process.stdout.write(`备份完成：${path}\n`);
} catch { process.stderr.write('备份失败：请检查数据库版本、安装包完整性和目标目录权限；原数据未被清除\n');process.exitCode = 1; }
