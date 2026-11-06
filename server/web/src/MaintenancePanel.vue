<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { z } from 'zod';
import { releaseView, upgradeView, commandView, errorText, labels, dateTime } from './api.ts';
import type { ApiClient, DeviceView } from './api.ts';
const props = defineProps<{ api: ApiClient; device: DeviceView }>();
const releases = ref<z.infer<typeof releaseView>[]>([]);const jobs = ref<z.infer<typeof upgradeView>[]>([]);const commands = ref<z.infer<typeof commandView>[]>([]);
const file = ref<File | null>(null);const target = ref('');const uploadBusy = ref(false);const upgradeBusy = ref(false);const rebootBusy = ref(false);const error = ref('');const uploadError = ref('');const uploadMessage = ref('');const dialog = ref<HTMLDialogElement | null>(null);
let commandId = '';let jobAttempt: { releaseId: string; id: string } | null = null;let epoch = 0;let poll: ReturnType<typeof setInterval> | undefined;
async function load() { const current = ++epoch;try { const result = await Promise.all([props.api.data('/releases', z.array(releaseView)), props.api.data(`/devices/${props.device.id}/upgrades`, z.array(upgradeView)), props.api.data(`/devices/${props.device.id}/commands`, z.array(commandView))]);if (epoch !== current) return;[releases.value, jobs.value, commands.value] = result;error.value = ''; }catch (e) { if (epoch === current) error.value = errorText(e); } }
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null;uploadMessage.value = '';uploadError.value = ''; }
async function upload() {
  if (!file.value || uploadBusy.value) return;uploadBusy.value = true;uploadError.value = '';uploadMessage.value = '';
  try { if (file.value.size > 100 * 1024 * 1024) { uploadError.value = '安装包超过100MiB限制';return; }const form = new FormData();form.set('apk', file.value);await props.api.data('/releases', releaseView, { method: 'POST', body: form });uploadMessage.value = '版本已发布';await load(); }
  catch (e) { uploadError.value = errorText(e); }finally { uploadBusy.value = false; }
}
async function queue() {
  if (!target.value || upgradeBusy.value) return;upgradeBusy.value = true;error.value = '';
  try { if (!jobAttempt || jobAttempt.releaseId !== target.value) jobAttempt = { releaseId: target.value, id: crypto.randomUUID() };await props.api.data(`/devices/${props.device.id}/upgrades`, upgradeView, { method: 'POST', body: { jobId: jobAttempt.id, releaseId: jobAttempt.releaseId } });jobAttempt = null;await load(); }
  catch (e) { error.value = errorText(e); }finally { upgradeBusy.value = false; }
}
function confirmReboot() { commandId = crypto.randomUUID();dialog.value?.showModal(); }
async function reboot() {
  if (rebootBusy.value) return;rebootBusy.value = true;error.value = '';
  try { await props.api.data(`/devices/${props.device.id}/reboot`, commandView, { method: 'POST', body: { commandId, confirmed: true } });dialog.value?.close();await load(); }
  catch (e) { error.value = errorText(e); }finally { rebootBusy.value = false; }
}
onMounted(() => { void load();poll = setInterval(() => { if (!document.hidden && !uploadBusy.value && !upgradeBusy.value && !rebootBusy.value) void load(); }, 15000); });
onUnmounted(() => { epoch++;clearInterval(poll);dialog.value?.close(); });
</script>
<template><section class="panel"><p v-if="error" class="error" role="alert">{{ error }}</p><section><h3>发布安装包</h3><p class="muted">只接受本应用的单 APK，最大100MiB。签名必须与现装版本一致。</p><form class="upload" @submit.prevent="upload"><label>选择 APK<input type="file" accept=".apk,application/vnd.android.package-archive" :disabled="uploadBusy" @change="chooseFile"></label><button :disabled="!file || uploadBusy">{{ uploadBusy ? '正在校验并发布…' : '发布版本' }}</button></form><p v-if="uploadError" class="error" role="alert">{{ uploadError }}</p><p v-if="uploadMessage" role="status">{{ uploadMessage }}</p></section>
<section><h3>安排设备升级</h3><p class="muted">离线设备可等待恢复连接。提交安装不代表已安装，结果以设备核实的现装版本为准。</p><form class="upload" @submit.prevent="queue"><label>目标版本<select v-model="target" :disabled="upgradeBusy"><option value="">请选择已发布版本</option><option v-for="release in releases" :key="release.id" :value="release.id" :disabled="release.versionCode <= device.versionCode || release.minSdk > device.apiLevel">{{ release.versionName }}（{{ release.versionCode }}） · {{ (release.size / 1024 / 1024).toFixed(1) }}MiB{{ release.versionCode <= device.versionCode ? ' · 已达到或更高' : '' }}</option></select></label><button class="primary" :disabled="!target || upgradeBusy">{{ upgradeBusy ? '安排中…' : '安排升级' }}</button></form><ul class="records"><li v-for="job in jobs" :key="job.id"><strong>{{ job.release.versionName }} · {{ labels[job.state] ?? job.state }}</strong><p>{{ job.result }}</p><small>{{ dateTime(job.createdAtMs) }}</small></li></ul><p v-if="!jobs.length" class="empty">暂无升级任务。</p><p class="muted">显示最近50项任务。</p></section>
<section><div class="section-title"><h3>整机重启</h3><button class="danger" :disabled="!device.online || rebootBusy" @click="confirmReboot">重启手机</button></div><p class="muted">{{ device.online ? '仅当前在线连接有效；断线后不会补发旧命令。' : '设备离线，不能发起重启。' }} 已发出、已尝试和已观察到后续启动是不同阶段。</p><ul class="records"><li v-for="command in commands" :key="command.id"><strong>{{ labels[command.state] ?? command.state }}</strong><p>{{ command.result }}</p><small>{{ dateTime(command.createdAtMs) }}</small></li></ul><p v-if="!commands.length" class="empty">暂无重启记录。</p><p class="muted">显示最近50项记录。</p></section>
<dialog ref="dialog" aria-labelledby="reboot-title"><h3 id="reboot-title">确认重启整台手机？</h3><p>{{ device.model }}</p><p class="muted wrap">{{ device.id }}</p><p>手机会立即尝试重启，无需现场确认。正在进行的操作可能中断。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><button :disabled="rebootBusy" @click="dialog?.close()">取消</button><button class="danger" :disabled="rebootBusy || !device.online" @click="reboot">{{ rebootBusy ? '发出请求中…' : '确认重启' }}</button></div></dialog>
</section></template>
