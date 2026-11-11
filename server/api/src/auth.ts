import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import type { FastifyInstance,FastifyRequest } from 'fastify';
import { ApiError } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { digest } from './device.ts';
import { decryptSecret,identitySchema } from './admin-identity.ts';
import { matchingCounter } from './totp.ts';
export { provisionAdmin } from './admin-identity.ts';
const loginSchema=z.object({username:z.string().min(1).max(100),code:z.string().regex(/^\d{6}$/)}).strict();
const sessionSchema=z.object({csrf:z.string(),createdAt:z.number(),lastAt:z.number(),generation:z.string().optional()});
const attemptSchema=z.object({count:z.int(),until:z.number()});
function identity(ctx:Context) {
 const raw=ctx.store.get('admins','administrator',z.record(z.string(),z.unknown()));const result=identitySchema.safeParse(raw);return result.success?result.data:null;
}
export function requireAdmin(ctx:Context,request:FastifyRequest,write=false):z.infer<typeof sessionSchema> {
 const token=request.cookies.ams_session;const key=token?digest(token):'';const session=key?ctx.store.get('sessions',key,sessionSchema):null;const now=ctx.now();const admin=identity(ctx);
 if(!session||!admin||session.generation!==admin.generation||now-session.lastAt>=30*60_000||now-session.createdAt>=12*60*60_000) {
  if(session)ctx.store.delete('sessions',key);throw new ApiError(401,'ADMIN_AUTH','请登录管理员账号');
 }
 if(write&&(request.headers.origin!==ctx.origin||request.headers['x-csrf-token']!==session.csrf))throw new ApiError(403,'CSRF','请求来源校验失败，请刷新页面');
 ctx.store.put('sessions',key,'',{...session,lastAt:now});return session;
}
export function authRoutes(app:FastifyInstance,ctx:Context,directory:string):void {
 const initial=identity(ctx);if(initial)decryptSecret(directory,initial.encryptedSecret);
 app.post('/api/v1/admin/login',(request,reply)=>{
  if(request.headers.origin!==ctx.origin)throw new ApiError(403,'ORIGIN','请求来源校验失败');
  const input=loginSchema.parse(request.body);const now=ctx.now();
  const result=ctx.store.transaction(()=>{
   ctx.store.db.prepare("DELETE FROM auth_attempts WHERE json_extract(data,'$.until')<=?").run(now);
   const keys=[`account:${digest(input.username)}`,`ip:${digest(request.ip)}`];
   const attempts=keys.map(key=>ctx.store.get('auth_attempts',key,attemptSchema)??{count:0,until:now+5*60_000});
   if(attempts.some((a,i)=>a.count>=(i===0?5:20)))return {limited:true} as const;
   keys.forEach((key,i)=>ctx.store.put('auth_attempts',key,'',{...attempts[i],count:attempts[i]!.count+1}));
   const admin=identity(ctx);const counter=admin?matchingCounter(decryptSecret(directory,admin.encryptedSecret),input.code,now,admin.lastCounter):null;
   if(!admin||admin.username!==input.username||counter===null)return {invalid:true} as const;
   ctx.store.put('admins','administrator','',{...admin,lastCounter:counter});keys.forEach(key=>ctx.store.delete('auth_attempts',key));
   ctx.store.db.prepare("DELETE FROM sessions WHERE json_extract(data,'$.lastAt')<? OR json_extract(data,'$.createdAt')<?").run(now-30*60_000,now-12*60*60_000);
   const token=randomBytes(32).toString('base64url');const session={csrf:randomBytes(32).toString('base64url'),createdAt:now,lastAt:now,generation:admin.generation};ctx.store.put('sessions',digest(token),'',session);return {token,session} as const;
  });
  if('limited' in result)throw new ApiError(429,'LOGIN_PAUSED','登录尝试过多，请5分钟后重试');
  if('invalid' in result)throw new ApiError(401,'LOGIN_FAILED','账号或动态验证码无效、已使用或已过期');
  reply.setCookie('ams_session',result.token,{secure:true,httpOnly:true,sameSite:'strict',path:'/api/v1/admin',maxAge:12*60*60});return {data:{csrf:result.session.csrf}};
 });
 app.get('/api/v1/admin/session',request=>({data:{csrf:requireAdmin(ctx,request).csrf}}));
 app.post('/api/v1/admin/logout',(request,reply)=>{requireAdmin(ctx,request,true);ctx.store.delete('sessions',digest(request.cookies.ams_session??''));reply.clearCookie('ams_session',{path:'/api/v1/admin'});return {data:{loggedOut:true}};});
}
