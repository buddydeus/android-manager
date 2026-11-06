<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { z } from 'zod';
import { currentSchema } from '../../contracts/index.ts';
import { ApiClient, deviceView, errorText, dateTime, HttpError } from './api.ts';
import type { DeviceView, Current } from './api.ts';
import LoginForm from './LoginForm.vue';
import DeviceConfig from './DeviceConfig.vue';
import UsagePanel from './UsagePanel.vue';
import MaintenancePanel from './MaintenancePanel.vue';
import './style.css';
const api = new ApiClient();const logged = ref(false);const loading = ref(true);const busy = ref(false);const error = ref('');
const devices = ref<DeviceView[]>([]);const total = ref(0);const page = ref(1);const selected = ref<DeviceView | null>(null);const snapshot = ref<Current | null | undefined>(undefined);
const tab = ref('配置');const dirty = ref(false);let generation = 0;let refreshing = false;let timer: ReturnType<typeof setInterval> | undefined;
api.onExpired = () => { generation++;logged.value = false;api.csrf = '';selected.value = null;snapshot.value = undefined;devices.value = [];dirty.value = false; };
async function refresh() {
  if (!logged.value || refreshing) return;refreshing = true;const epoch = generation;const id = selected.value?.id;
  try {
    const list = await api.json(`/devices?page=${page.value}&limit=30`, z.object({ data: z.array(deviceView), total: z.number() }));
    if (epoch !== generation) return;devices.value = list.data;total.value = list.total;
    if (id) { const [detail, config] = await Promise.all([api.data(`/devices/${id}`, deviceView), api.data(`/devices/${id}/config`, currentSchema.nullable())]);if (epoch !== generation || id !== selected.value?.id) return;selected.value = detail;snapshot.value = config; }
    error.value = '';
  } catch (e) { if (epoch === generation) error.value = errorText(e); }
  finally { refreshing = false; }
}
async function login(username: string, password: string) {
  busy.value = true;error.value = '';
  try { const session = await api.data('/login', z.object({ csrf: z.string() }), { method: 'POST', body: { username, password } });api.csrf = session.csrf;logged.value = true;await refresh(); }
  catch (e) { error.value = errorText(e); }finally { busy.value = false; }
}
async function choose(device: DeviceView) {
  if (device.id === selected.value?.id) return;
  if (dirty.value && !window.confirm('当前配置尚未保存，是否丢弃草稿并切换设备？')) return;
  generation++;selected.value = device;snapshot.value = undefined;dirty.value = false;tab.value = '配置';
  // Let an older refresh finish; its generation cannot replace this selection.
  const epoch = generation;
  try { const [detail, config] = await Promise.all([api.data(`/devices/${device.id}`, deviceView), api.data(`/devices/${device.id}/config`, currentSchema.nullable())]);if (epoch !== generation) return;selected.value = detail;snapshot.value = config;error.value = ''; }
  catch (e) { if (epoch === generation) error.value = errorText(e); }
}
function acceptSaved(deviceId: string, current: Current) {
  if (selected.value?.id !== deviceId) return;
  generation++;snapshot.value = current;void refresh();
}
async function logout() {
  if (dirty.value && !window.confirm('当前配置尚未保存，是否丢弃草稿并退出？')) return;
  try { await api.data('/logout', z.object({ loggedOut: z.boolean() }), { method: 'POST' });api.onExpired(); }catch (e) { error.value = errorText(e); }
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault();event.returnValue = ''; } }
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload);
  try { const session = await api.data('/session', z.object({ csrf: z.string() }));api.csrf = session.csrf;logged.value = true;await refresh(); }
  catch (e) { if (!(e instanceof HttpError && e.status === 401)) error.value = errorText(e); }
  finally { loading.value = false; }
  timer = setInterval(() => { if (!document.hidden) void refresh(); }, 15000);
});
onUnmounted(() => { clearInterval(timer);window.removeEventListener('beforeunload', beforeUnload); });
</script>
<template>
  <main v-if="loading" class="login" aria-live="polite">正在检查登录状态…</main>
  <LoginForm v-else-if="!logged" :busy="busy" :error="error" @login="login" />
  <div v-else class="console"><header class="top"><div><strong>应用管控</strong><span class="muted">远程管理</span></div><button @click="logout">退出登录</button></header>
    <p v-if="error" class="error global-error" role="alert">{{ error }}；已显示的数据可能尚未刷新。</p>
    <div class="workspace"><aside><div class="section-title"><h1>设备</h1><button @click="refresh">刷新</button></div><p class="muted">{{ total }} 台已注册设备</p>
      <p v-if="!devices.length" class="empty">暂无设备。完成手机初始化后会自动接入。</p>
      <ul class="devices"><li v-for="device in devices" :key="device.id"><button :class="{ selected: selected?.id === device.id }" @click="choose(device)"><span>{{ device.model }}</span><small>{{ device.online ? '在线' : '离线' }} · {{ device.versionName }} · {{ device.id.slice(0, 8) }}</small></button></li></ul>
      <div class="pagination"><button :disabled="page === 1" @click="page--;refresh()">上一页</button><span>{{ page }}</span><button :disabled="page * 30 >= total" @click="page++;refresh()">下一页</button></div>
    </aside><main class="detail"><template v-if="selected"><div class="device-heading"><div><h2>{{ selected.model }}</h2><p class="muted wrap">{{ selected.id }}</p></div><span class="badge">{{ selected.online ? '在线' : '离线' }}</span></div>
      <dl class="facts"><div><dt>系统启动时间（估算）</dt><dd>{{ dateTime(selected.status.bootStartedAtMs) }}</dd></div><div><dt>最近联系</dt><dd>{{ dateTime(selected.lastSeenAt) }}</dd></div><div><dt>当前版本</dt><dd>{{ selected.versionName }}（{{ selected.versionCode }}）</dd></div></dl>
      <p v-if="selected.status.syncError" class="error" role="status">{{ selected.status.syncError }}</p>
      <nav class="tabs" aria-label="设备页面"><button v-for="name in ['配置', '使用记录', '维护']" :key="name" :aria-current="tab === name ? 'page' : undefined" @click="tab = name">{{ name }}<span v-if="name === '配置' && dirty"> · 未保存</span></button></nav>
      <DeviceConfig v-if="snapshot !== undefined" v-show="tab === '配置'" :key="selected.id" :api="api" :device="selected" :current="snapshot" @dirty="dirty = $event" @saved="acceptSaved" />
      <p v-else-if="tab === '配置'" class="empty">{{ error ? '配置未能读取，请刷新重试。' : '正在读取配置…' }}</p>
      <UsagePanel v-if="tab === '使用记录'" :key="selected.id" :api="api" :device-id="selected.id" />
      <MaintenancePanel v-if="tab === '维护'" :key="selected.id" :api="api" :device="selected" />
    </template><div v-else class="empty start"><h2>选择一台设备</h2><p>查看规则、使用记录和维护结果。</p></div></main></div>
  </div>
</template>
