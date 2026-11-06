import { test, expect } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
async function credentials() { return JSON.parse(await readFile(join(import.meta.dirname, '../../node_modules/.cache/ams-browser.json'), 'utf8')) as { username: string; password: string; apk?: string }; }
test('administrator can edit configuration, inspect missing usage, publish and queue, while offline reboot stays disabled', async ({ page }) => {
  const fixture = await credentials(); await page.goto('/');
  await page.getByLabel('管理员账号').fill(fixture.username);await page.getByLabel('管理员密码').fill(fixture.password);await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('button', { name: /^测试手机/ }).click();
  await expect(page.getByRole('button', { name: '保存配置', exact: true })).toBeDisabled();
  await expect(page.getByLabel('星期一开始')).toHaveValue('08:00');
  await page.getByLabel('管控总开关').check();await page.getByRole('button', { name: '保存配置', exact: true }).click();
  await expect(page.getByText('配置已保存，请查看设备接收与执行结果。')).toBeVisible();
  await expect(page.getByText('等待设备接收', { exact: true })).toBeVisible();
  await expect(page.getByText(/<img src=x onerror=alert\(1\)>/).first()).toBeVisible();
  await expect(page.locator('img')).toHaveCount(0);
  await page.getByRole('button', { name: '使用记录', exact: true }).click();await expect(page.getByText('存在记录缺失，合计仅包含完整记录。')).toBeVisible();
  await page.getByRole('button', { name: '维护', exact: true }).click();
  await page.getByLabel('选择 APK').setInputFiles({ name: 'bad.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('invalid') });await page.getByRole('button', { name: '发布版本', exact: true }).click();await expect(page.getByText('安装包文件无效', { exact: true })).toBeVisible();
  if (fixture.apk) {
    await page.getByLabel('选择 APK').setInputFiles(fixture.apk);await page.getByRole('button', { name: '发布版本', exact: true }).click();await expect(page.getByText('版本已发布', { exact: true })).toBeVisible({ timeout: 20000 });
    await page.getByLabel('目标版本').selectOption({ index: 1 });await page.getByRole('button', { name: '安排升级', exact: true }).click();await expect(page.getByText(/已排队/).first()).toBeVisible();
  }
  let rebootRequests = 0;page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/reboot')) rebootRequests++; });
  await page.getByRole('button', { name: '重启手机', exact: true }).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('button', { name: '取消', exact: true }).click();expect(rebootRequests).toBe(0);
  await page.getByRole('button', { name: '重启手机', exact: true }).click();await page.getByRole('button', { name: '确认重启', exact: true }).click();await expect(page.getByText('已发出', { exact: true })).toBeVisible();expect(rebootRequests).toBe(1);
  await page.getByRole('button', { name: /^离线手机/ }).click();await page.getByRole('button', { name: '维护', exact: true }).click();await expect(page.getByRole('button', { name: '重启手机', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '配置', exact: true }).click();await page.getByLabel('管控总开关').check();await page.getByRole('button', { name: '保存配置', exact: true }).click();await expect(page.getByText('服务器保留了更新时间较新的配置；本次草稿未生效，请核对当前规则。')).toBeVisible();
});
test('narrow screen keeps login and device navigation within the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });const fixture = await credentials();await page.goto('/');
  await page.getByLabel('管理员账号').fill(fixture.username);await page.getByLabel('管理员密码').fill(fixture.password);await page.getByRole('button', { name: '登录', exact: true }).click();await page.getByRole('button', { name: /^测试手机/ }).click();
  await expect(page.getByLabel('管控总开关')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await mkdir(join(import.meta.dirname, '../../../designs/validation/remote-console'), { recursive: true });
  await page.screenshot({ path: join(import.meta.dirname, '../../../designs/validation/remote-console/mobile.png'), fullPage: true });
});
test('retry after a lost save response reuses its mutation identity', async ({ page }) => {
  const fixture = await credentials();await page.goto('/');await page.getByLabel('管理员账号').fill(fixture.username);await page.getByLabel('管理员密码').fill(fixture.password);await page.getByRole('button', { name: '登录', exact: true }).click();await page.getByRole('button', { name: /^测试手机/ }).click();
  const ids: string[] = [];
  await page.route('**/api/v1/admin/devices/*/config', async route => {
    if (route.request().method() !== 'PUT') { await route.continue();return; }
    ids.push(route.request().headers()['x-mutation-id']);
    if (ids.length === 1) { await route.fetch();await route.abort('failed'); }else await route.continue();
  });
  const toggle = page.getByLabel('管控总开关');await toggle.setChecked(!await toggle.isChecked());await page.getByRole('button', { name: '保存配置', exact: true }).click();await expect(page.getByText(/连接或响应异常/)).toBeVisible();await page.getByRole('button', { name: '保存配置', exact: true }).click();await expect(page.getByText('配置已保存，请查看设备接收与执行结果。')).toBeVisible();expect(ids).toHaveLength(2);expect(ids[1]).toBe(ids[0]);
  await mkdir(join(import.meta.dirname, '../../../designs/validation/remote-console'), { recursive: true });
  await page.screenshot({ path: join(import.meta.dirname, '../../../designs/validation/remote-console/desktop.png'), fullPage: true });
});
test('a configuration read started before a successful save cannot restore old form values', async ({ page }) => {
  const fixture = await credentials();await page.goto('/');await page.getByLabel('管理员账号').fill(fixture.username);await page.getByLabel('管理员密码').fill(fixture.password);await page.getByRole('button', { name: '登录', exact: true }).click();await page.getByRole('button', { name: /^测试手机/ }).click();await expect(page.getByRole('button', { name: '保存配置', exact: true })).toBeDisabled();
  let release: () => void = () => {};let captured: () => void = () => {};let intercepted = false;
  const blocked = new Promise<void>(resolve => { release = resolve; });const observed = new Promise<void>(resolve => { captured = resolve; });
  await page.route('**/api/v1/admin/devices/*/config', async route => {
    if (route.request().method() === 'GET' && !intercepted) { intercepted = true;const response = await route.fetch();captured();await blocked;await route.fulfill({ response }); }else await route.continue();
  });
  await page.getByRole('button', { name: '刷新', exact: true }).click();await observed;
  const toggle = page.getByLabel('管控总开关');const expected = !await toggle.isChecked();await toggle.setChecked(expected);await page.getByRole('button', { name: '保存配置', exact: true }).click();await expect(page.getByText('配置已保存，请查看设备接收与执行结果。')).toBeVisible();
  const stale = page.waitForResponse(response => response.request().method() === 'GET' && response.url().endsWith('/config'));release();await (await stale).finished();await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  if (expected) await expect(toggle).toBeChecked();else await expect(toggle).not.toBeChecked();
});
