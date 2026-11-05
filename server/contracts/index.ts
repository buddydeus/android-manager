import { z } from 'zod';

export const idSchema = z.uuid();
export const packageSchema = z.string().max(255).regex(/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/);
export const daySchema = z.object({ startMinute: z.int().min(0).max(1439), endMinute: z.int().min(0).max(1439) }).strict().refine(x => x.startMinute !== x.endMinute);
export const configSchema = z.object({
  enabled: z.boolean(), blacklist: z.array(packageSchema).max(5000).transform(x => [...new Set(x)].sort()),
  schedule: z.array(daySchema.nullable()).length(7),
}).strict();
export const candidateSchema = z.object({
  mutationId: idSchema, modifiedAtMs: z.int().nonnegative(),
  timeQuality: z.enum(['CALIBRATED', 'ESTIMATED_AFTER_BOOT', 'UNCALIBRATED']),
  origin: z.enum(['LOCAL', 'REMOTE', 'MIGRATED_BASELINE']), config: configSchema,
}).strict();
export const currentSchema = candidateSchema.extend({ serverRevision: z.int().positive() });
export type Candidate = z.infer<typeof candidateSchema>;
export type CurrentConfig = z.infer<typeof currentSchema>;
export const deviceSchema = z.object({
  id: idSchema, installId: idSchema, publicKey: z.string(), model: z.string().max(200),
  apiLevel: z.int().min(30).max(100), versionCode: z.int().positive(), versionName: z.string().max(100),
  registeredAt: z.number(), lastSeenAt: z.number().nullable(),
  status: z.record(z.string(), z.unknown()).default({}),
});
export type Device = z.infer<typeof deviceSchema>;
export const challengeInput = z.object({ installId: idSchema, publicKey: z.string().min(40).max(1024) }).strict();
export const registerSchema = challengeInput.extend({
  challengeId: idSchema, signature: z.string().max(512), model: z.string().min(1).max(200),
  apiLevel: z.int().min(30).max(100), versionCode: z.int().positive(), versionName: z.string().max(100),
}).strict();

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) { super(message); this.status = status; this.code = code; }
}
