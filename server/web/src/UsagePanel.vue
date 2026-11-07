<script setup lang="ts">
import { ref, watch } from 'vue';
import { usageResult, usageClearResult, dateTime, duration, errorText, labels, today } from './api.ts';
import type { ApiClient } from './api.ts';
import type { z } from 'zod';
type UsageSession = z.infer<typeof usageResult>['sessions'][number];
function startText(session: UsageSession): string { return session.startedAtMs === null ? '未记录开始时间' : dateTime(session.startedAtMs); }
function endText(session: UsageSession): string {
  if (session.endedAtMs !== null) return dateTime(session.endedAtMs);
  return session.quality === 'IN_PROGRESS' ? '使用中，尚未息屏' : '未记录结束时间';
}
function durationText(session: UsageSession): string {
  if (session.durationMs !== null) return duration(session.durationMs);
  if (session.quality === 'IN_PROGRESS') return session.startedAtMs === null ? '使用中，开始时间未记录' : '使用中，息屏后统计';
  if (session.startedAtMs === null && session.endedAtMs === null) return '无法计算（起止时间未记录）';
  if (session.startedAtMs === null) return '无法计算（缺少开始时间）';
  if (session.endedAtMs === null) return '无法计算（缺少结束时间）';
  return '无法计算（计时记录不完整）';
}
const props = defineProps<{ api: ApiClient; deviceId: string }>();const date = ref(today());const page = ref(1);const data = ref<z.infer<typeof usageResult> | null>(null);const busy = ref(false);const error = ref('');const clearing = ref(false);const message = ref('');let epoch = 0;
async function load() { const current = ++epoch;busy.value = true;error.value = '';data.value = null;try { const result = await props.api.data(`/devices/${props.deviceId}/usage?date=${date.value}&page=${page.value}`, usageResult);if (epoch === current) data.value = result; }catch (e) { if (epoch === current) error.value = errorText(e); }finally { if (epoch === current) busy.value = false; } }
async function clearDay() {
  if (busy.value || clearing.value) return;
  clearing.value = true;error.value = '';message.value = '';
  const selectedDate = date.value;
  try {
    await props.api.data(`/devices/${props.deviceId}/usage?date=${selectedDate}`, usageClearResult, { method: 'DELETE' });
    message.value = `${selectedDate} 的记录已清空`;page.value = 1;await load();
  }catch (e) { error.value = errorText(e); }finally { clearing.value = false; }
}
watch(date, () => { message.value = '';page.value = 1;void load(); });watch(page, () => { if (!clearing.value) void load(); });load();
</script>
<template><section class="panel"><div class="section-title"><h3>使用记录</h3><div class="usage-actions"><button :disabled="busy || clearing" @click="load">刷新记录</button><button class="danger" :disabled="busy || clearing || !data" @click="clearDay">{{ clearing ? '清空中…' : '清空记录' }}</button></div></div><label class="date-filter">日期（上海时区）<input v-model="date" type="date" :disabled="clearing" required></label><p class="muted">保留最近90天。解锁后开始，息屏结束，亮屏闲置也计入。跨日会话按当天交集计入合计，明细显示整次时长。</p>
<p class="muted">清空仅影响当前设备所选日期的记录与合计，之后的新使用继续记录；跨日会话的其他日期保留。</p>
<p v-if="message" role="status">{{ message }}</p><p v-if="busy" aria-live="polite">正在读取记录…</p><p v-if="error" class="error" role="alert">{{ error }}</p><template v-if="data"><dl class="facts"><div><dt>完整时长</dt><dd>{{ duration(data.completeDurationMs) }}</dd></div><div><dt>不完整记录</dt><dd>{{ data.incompleteCount }} 条</dd></div><div><dt>进行中</dt><dd>{{ data.activeCount }} 条</dd></div></dl><p v-if="data.incompleteCount || data.gaps.length" class="warning" role="status">存在记录缺失，合计仅包含完整记录。</p>
<div class="table-scroll"><table><caption class="sr-only">当天使用会话</caption><thead><tr><th>开始</th><th>结束</th><th>本次时长</th><th>记录状态</th></tr></thead><tbody><tr v-for="session in data.sessions" :key="session.id"><td>{{ startText(session) }}</td><td>{{ endText(session) }}</td><td>{{ durationText(session) }}</td><td>{{ labels[session.quality] ?? session.quality }}<small v-if="session.source === 'HISTORY_ESTIMATED'">系统历史估算</small><small v-if="session.clockAdjusted">时钟曾调整，时长按单调计时</small></td></tr></tbody></table></div><p v-if="!data.sessions.length" class="empty">所选日期没有已保存的会话。请结合覆盖缺口判断。</p><div class="pagination"><button :disabled="busy || clearing || page === 1" @click="page--">上一页</button><span>{{ page }}</span><button :disabled="busy || clearing || page * 50 >= data.total" @click="page++">下一页</button></div>
<details v-if="data.gaps.length"><summary>查看监测缺口（{{ data.gaps.length }} 段，最多显示200段）</summary><ul><li v-for="gap in data.gaps" :key="gap.id">{{ dateTime(gap.startedAtMs) }} 至 {{ dateTime(gap.endedAtMs) }}：{{ gap.reason === 'INITIAL_COVERAGE_UNKNOWN' ? '开始监测前的使用情况未记录' : '监听中断或无法可靠补齐' }}</li></ul></details></template></section></template>

<style scoped>
.usage-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
</style>
