import { randomBytes, randomUUID, createCipheriv, createDecipheriv } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { join } from 'node:path';
import { z } from 'zod';
import { Store } from './db.ts';
import { matchingCounter, newTotpSecret } from './totp.ts';
export const identitySchema=z.object({ kind:z.literal('TOTP'),version:z.literal(1),username:z.string().min(1).max(100),encryptedSecret:z.string(),generation:z.string(),lastCounter:z.int().min(-1) }).strict();
const pendingSchema=z.object({ username:z.string().min(1).max(100),encryptedSecret:z.string(),createdAtMs:z.number(),generation:z.string() }).strict();
function key(directory:string,create=false):Buffer {
 const path=join(directory,'auth.key');
 if(create&&!existsSync(path))writeFileSync(path,randomBytes(32),{mode:0o600,flag:'wx'});
 const value=readFileSync(path);if(value.length!==32)throw new Error('Invalid authentication key');return value;
}
function encrypt(directory:string,secret:string,create=false):string {
 const iv=randomBytes(12);const cipher=createCipheriv('aes-256-gcm',key(directory,create),iv);
 const data=Buffer.concat([cipher.update(secret,'utf8'),cipher.final()]);return Buffer.concat([iv,cipher.getAuthTag(),data]).toString('base64');
}
export function decryptSecret(directory:string,encrypted:string):string {
 const data=Buffer.from(encrypted,'base64');if(data.length<29)throw new Error('Invalid encrypted identity');
 const cipher=createDecipheriv('aes-256-gcm',key(directory),data.subarray(0,12));cipher.setAuthTag(data.subarray(12,28));return Buffer.concat([cipher.update(data.subarray(28)),cipher.final()]).toString('utf8');
}
export function provisionAdmin(directory:string,username:string,secret:string):void {
 // Explicit initial provisioning API; no password verifier or password-login fallback.
 matchingCounter(secret,'000000',Date.now(),-1);
 const store=new Store(directory);try{store.transaction(()=>{
  if(store.db.prepare("SELECT 1 FROM admins WHERE id='administrator'").get())throw new Error('Administrator exists');
  const value=identitySchema.parse({kind:'TOTP',version:1,username,encryptedSecret:encrypt(directory,secret,true),generation:randomUUID(),lastCounter:-1});store.put('admins','administrator','',value);
 });}finally{store.close();}
}
export function beginEnrollment(directory:string,username:string,reset=false,now=Date.now(),preparedSecret?:string):string {
 const store=new Store(directory);try{return store.transaction(()=>{
  const prior=store.get('admins','administrator',z.record(z.string(),z.unknown()));
  if(prior?.kind==='TOTP'&&!reset)throw new Error('Use explicit reset for an active identity');
  if(prior&&prior.username!==username)throw new Error('Existing username must be preserved');
  if(store.db.prepare("SELECT 1 FROM admins WHERE id='totp-pending'").get())throw new Error('Pending enrollment exists; confirm or cancel it');
  if(prior?.kind==='TOTP')key(directory); // Never recreate a lost key for an active identity.
  const secret=preparedSecret??newTotpSecret();matchingCounter(secret,'000000',now,-1);const candidate={username,encryptedSecret:encrypt(directory,secret,true),createdAtMs:now,generation:randomUUID()};
  const path=join(directory,'totp-enrollment.json');
  writeFileSync(path,JSON.stringify({username,secret,uri:`otpauth://totp/${encodeURIComponent('AMS:'+username)}?secret=${secret}&issuer=AMS&algorithm=SHA1&digits=6&period=30`}),{mode:0o600});chmodSync(path,0o600);
  store.put('admins','totp-pending','',candidate);return path;
 });}finally{store.close();}
}
export function confirmEnrollment(directory:string,code:string,now=Date.now()):void {
 const store=new Store(directory);try{store.transaction(()=>{
  const pending=store.get('admins','totp-pending',pendingSchema);if(!pending)throw new Error('No pending enrollment');
  if(now-pending.createdAtMs>86400000||now<pending.createdAtMs)throw new Error('Enrollment expired; cancel and restart');
  const counter=matchingCounter(decryptSecret(directory,pending.encryptedSecret),code,now,-1);if(counter===null)throw new Error('Invalid code');
  store.put('admins','administrator','',identitySchema.parse({kind:'TOTP',version:1,username:pending.username,encryptedSecret:pending.encryptedSecret,generation:pending.generation,lastCounter:counter}));store.delete('admins','totp-pending');store.db.exec('DELETE FROM sessions');
 });}finally{store.close();}
}
