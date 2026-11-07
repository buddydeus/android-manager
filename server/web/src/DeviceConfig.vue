<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { z } from 'zod';
import { configSchema, currentSchema } from '../../contracts/index.ts';
import { appsResult, errorText, dateTime } from './api.ts';
import type { ApiClient, Current, DeviceView } from './api.ts';
const props = defineProps<{ api: ApiClient; device: DeviceView; current: Current | null }>();const emit = defineEmits<{ dirty: [value: boolean]; saved: [deviceId: string, value: Current] }>();
type Config = z.infer<typeof configSchema>;const draft = ref<Config>({ enabled: false, blacklist: [], schedule: Array(7).fill(null) });
const base = ref('');const busy = ref(false);const error = ref('');const message = ref('');const search = ref('');const page = ref(1);const apps = ref<z.infer<typeof appsResult> | null>(null);const appsBusy = ref(false);const appsError = ref('');
const signature = computed(() => JSON.stringify(draft.value));const dirty = computed(() => base.value !== '' && props.current !== null && signature.value !== base.value);
let attempt: { signature: string; id: string } | null = null;let catalogEpoch = 0;
const days = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];
function reset() { if (props.current) { draft.value = configSchema.parse(props.current.config);base.value = JSON.stringify(draft.value);attempt = null; } }
watch(() => props.current?.mutationId, () => { if (!dirty.value) reset(); }, { immediate: true });
watch(dirty, value => emit('dirty', value), { immediate: true });
const remoteChanged = computed(() => !!props.current && JSON.stringify(props.current.config) !== base.value);
const received = computed(() => props.current?.mutationId === props.device.status.configMutationId);
const executed = computed(() => received.value && props.device.status.policyRevision === props.device.status.configRevision);
function time(minute: number) { return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`; }
function setDay(index: number, enabled: boolean) { draft.value = { ...draft.value, schedule: draft.value.schedule.map((day, i) => i === index ? enabled ? { startMinute: 0, endMinute: 1439 } : null : day) }; }
function setTime(index: number, field: 'startMinute' | 'endMinute', value: string) { if (!/^\d{2}:\d{2}$/.test(value)) return;const [h, m] = value.split(':').map(Number);draft.value = { ...draft.value, schedule: draft.value.schedule.map((day, i) => i === index && day ? { ...day, [field]: h * 60 + m } : day) }; }
function blacklist(pkg: string, selected: boolean) { draft.value = { ...draft.value, blacklist: selected ? [...new Set([...draft.value.blacklist, pkg])].sort() : draft.value.blacklist.filter(p => p !== pkg) }; }
async function loadApps() {
  const epoch = ++catalogEpoch;appsBusy.value = true;appsError.value = '';
  try { const result = await props.api.data(`/devices/${props.device.id}/apps?search=${encodeURIComponent(search.value)}&page=${page.value}&limit=50`, appsResult);if (epoch === catalogEpoch) apps.value = result; }
  catch (e) { if (epoch === catalogEpoch) { apps.value = null;appsError.value = errorText(e); } }finally { if (epoch === catalogEpoch) appsBusy.value = false; }
}
watch(page, loadApps);loadApps();
async function save() {
  if (busy.value) return;busy.value = true;error.value = '';message.value = '';
  try {
    const parsed = configSchema.safeParse(draft.value);if (!parsed.success) { error.value = '请检查时间表：起止时间不能相同，时间须有效。';return; }
    const serialized = JSON.stringify(parsed.data);if (!attempt || attempt.signature !== serialized) attempt = { signature: serialized, id: crypto.randomUUID() };
    const saved = await props.api.data(`/devices/${props.device.id}/config`, currentSchema, { method: 'PUT', body: parsed.data, mutationId: attempt.id });
    draft.value = structuredClone(saved.config);base.value = JSON.stringify(draft.value);attempt = null;emit('saved', props.device.id, saved);message.value = JSON.stringify(saved.config) === serialized ? '配置已保存，请查看设备接收与执行结果。' : '服务器保留了更新时间较新的配置；本次草稿未生效，请核对当前规则。';
  } catch (e) { error.value = errorText(e); }finally { busy.value = false; }
}
</script>
<template><section v-if="current" class="panel">
  <section class="result-strip" aria-label="配置执行状态"><div><strong>{{ !received ? '等待设备接收' : !executed ? '已接收，等待执行' : '设备已回报执行结果' }}</strong><p>{{ received ? device.status.policyResult : '配置保存不代表系统限制已生效。' }}</p></div><small>配置版本 {{ current.serverRevision }}<br>{{ dateTime(current.modifiedAtMs) }}</small></section>
  <p v-if="received && device.status.alarmStatus" class="warning" role="status">{{ device.status.alarmStatus }}</p>
  <p v-if="remoteChanged && dirty" class="warning" role="status">服务器配置已变化。当前草稿仍保留，保存时将按本次修改时间比较。<button :disabled="busy" @click="reset">丢弃草稿并读取服务器配置</button></p>
  <form @submit.prevent="save"><div class="section-title"><h3>管控规则</h3><span class="muted">{{ dirty ? '有未保存的修改' : '与当前配置一致' }}</span></div>
    <label class="toggle"><input v-model="draft.enabled" type="checkbox" :disabled="busy">管控总开关</label><p class="muted">远程管理与使用统计持续运行。关闭总开关会解除本软件施加的限制并保留规则。</p>
    <h3>每周时间表</h3><p class="muted">上海时区；结束分钟完整计入。起止不能相同，结束早于开始表示跨午夜。</p>
    <div class="schedule"><div v-for="(name, index) in days" :key="name" class="day"><label><input type="checkbox" :checked="draft.schedule[index] !== null" :disabled="busy" @change="setDay(index, ($event.target as HTMLInputElement).checked)">{{ name }}</label><div class="times"><input type="time" :aria-label="`${name}开始`" :value="draft.schedule[index] ? time(draft.schedule[index]!.startMinute) : ''" :disabled="busy || !draft.schedule[index]" required @input="setTime(index, 'startMinute', ($event.target as HTMLInputElement).value)"><span>至</span><input type="time" :aria-label="`${name}结束`" :value="draft.schedule[index] ? time(draft.schedule[index]!.endMinute) : ''" :disabled="busy || !draft.schedule[index]" required @input="setTime(index, 'endMinute', ($event.target as HTMLInputElement).value)"></div><button type="button" :disabled="busy" @click="setDay(index, true)">全天</button></div></div>
    <h3>应用黑名单</h3><div class="search"><label>搜索设备应用<input v-model="search" type="search" maxlength="200" @keydown.enter.prevent="page = 1;loadApps()"></label><button type="button" @click="page = 1;loadApps()">搜索</button></div>
    <p v-if="appsError" class="error" role="alert">{{ appsError }}</p><p v-if="appsBusy" aria-live="polite">正在读取应用目录…</p>
    <template v-else-if="apps"><p class="muted">目录更新：{{ apps.atMs === null ? '设备尚未上传' : dateTime(apps.atMs) }}</p><ul class="apps"><li v-for="app in apps.entries" :key="app.packageName"><label><input type="checkbox" :checked="draft.blacklist.includes(app.packageName)" :disabled="busy || (!!app.protection && !draft.blacklist.includes(app.packageName))" @change="blacklist(app.packageName, ($event.target as HTMLInputElement).checked)"><span>{{ app.label }}<small class="wrap">{{ app.packageName }} · {{ app.installed ? '已安装' : '已卸载' }}{{ app.protection ? ` · ${app.protection}` : '' }}</small></span></label></li></ul><p v-if="!apps.entries.length" class="empty">没有匹配应用。</p><div class="pagination"><button type="button" :disabled="page === 1" @click="page--">上一页</button><span>{{ page }}</span><button type="button" :disabled="page * 50 >= apps.total" @click="page++">下一页</button></div></template>
    <div class="save"><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p><button class="primary" :disabled="busy || !dirty">{{ busy ? '保存中…' : '保存配置' }}</button></div>
  </form></section><p v-else class="empty">等待设备首次上传本机配置，之后可在这里修改。</p></template>
