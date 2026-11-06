import { z } from 'zod';
import { currentSchema, deviceSchema } from '../../contracts/index.ts';
export const deviceView = deviceSchema.extend({ online: z.boolean(), status: z.object({ bootId: z.int().optional(), bootStartedAtMs: z.number().nullable().optional(), configMutationId: z.string().nullable().optional(), configRevision: z.number().optional(), policyRevision: z.number().optional(), policyResult: z.string().optional(), alarmStatus: z.string().optional(), syncError: z.string().optional() }).passthrough() });
export type DeviceView = z.infer<typeof deviceView>;
export type Current = z.infer<typeof currentSchema>;
export const appEntry = z.object({ packageName: z.string(), label: z.string(), installed: z.boolean(), system: z.boolean(), protection: z.string() });
export const appsResult = z.object({ entries: z.array(appEntry), total: z.number(), atMs: z.number().nullable() });
export const usageResult = z.object({ sessions: z.array(z.object({ id: z.string(), startedAtMs: z.number().nullable(), endedAtMs: z.number().nullable(), durationMs: z.number().nullable(), quality: z.string(), source: z.string(), clockAdjusted: z.boolean() })), gaps: z.array(z.object({ id: z.string(), startedAtMs: z.number(), endedAtMs: z.number(), reason: z.string() })), completeDurationMs: z.number(), incompleteCount: z.number(), activeCount: z.number(), total: z.number() });
export const releaseView = z.object({ id: z.string(), versionCode: z.number(), versionName: z.string(), minSdk: z.number(), size: z.number(), createdAtMs: z.number() });
export const upgradeView = z.object({ id: z.string(), release: releaseView, state: z.string(), result: z.string(), createdAtMs: z.number() });
export const commandView = z.object({ id: z.string(), state: z.string(), result: z.string(), createdAtMs: z.number() });
export class HttpError extends Error { status: number; constructor(status: number, message: string) { super(message); this.status = status; } }
export class ApiClient {
  csrf = ''; onExpired: () => void = () => {};
  async json<T>(path: string, schema: z.ZodType<T>, options: { method?: string; body?: unknown; mutationId?: string } = {}): Promise<T> {
    const sessionAtStart = this.csrf;
    const headers: Record<string, string> = {};const form = options.body instanceof FormData;
    if (options.body !== undefined && !form) headers['Content-Type'] = 'application/json';
    if (options.method && options.method !== 'GET') headers['X-CSRF-Token'] = this.csrf;
    if (options.mutationId) headers['X-Mutation-Id'] = options.mutationId;
    const response = await fetch(`/api/v1/admin${path}`, { method: options.method ?? 'GET', body: options.body === undefined ? undefined : form ? options.body as FormData : JSON.stringify(options.body), headers, credentials: 'same-origin', signal: AbortSignal.timeout(60000) });
    if (response.status === 401 && sessionAtStart === this.csrf) this.onExpired();
    const raw: unknown = await response.json();
    if (!response.ok) { const error = z.object({ message: z.string() }).safeParse(raw);throw new HttpError(response.status, error.success ? error.data.message : '请求失败，请稍后重试'); }
    return schema.parse(raw);
  }
  async data<T>(path: string, schema: z.ZodType<T>, options: { method?: string; body?: unknown; mutationId?: string } = {}): Promise<T> { return (await this.json(path, z.object({ data: schema }), options)).data; }
}
export function errorText(error: unknown): string { return error instanceof HttpError ? error.message : error instanceof z.ZodError ? '服务器数据格式异常，请检查服务版本' : '连接或响应异常，请重试；已提交操作请先查看结果'; }
export function dateTime(ms: number | null | undefined): string { return ms == null ? '未知' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(ms); }
export function duration(ms: number | null): string { if (ms === null) return '未知';const seconds = Math.floor(ms / 1000);return `${Math.floor(seconds / 3600)}小时${Math.floor(seconds % 3600 / 60)}分${seconds % 60}秒`; }
export function today(): string { const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts();const get = (type: string) => parts.find(p => p.type === type)?.value;return `${get('year')}-${get('month')}-${get('day')}`; }
export const labels: Record<string, string> = { QUEUED: '已排队', DOWNLOADING: '下载中', VERIFIED: '校验通过', INSTALLING: '安装中', SUCCEEDED: '已核实安装', FAILED: '失败', UNKNOWN: '结果未知', SUPERSEDED: '已有相同或更新版本', DISPATCHED: '已发出', ACCEPTED: '设备已接收', PERMITTED: '已许可', ATTEMPTED: '已尝试，等待证据', OBSERVED_LATER_BOOT: '已观察到后续启动', NOT_DELIVERED: '未送达', IN_PROGRESS: '进行中', COMPLETE: '完整', MISSING_START: '缺少开始', MISSING_END: '缺少结束', COVERAGE_UNKNOWN: '覆盖不明' };
