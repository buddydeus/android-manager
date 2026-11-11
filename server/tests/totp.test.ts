import { test } from 'node:test';
import assert from 'node:assert/strict';
import { totp, matchingCounter } from '../api/src/totp.ts';
test('RFC6238 SHA1 vectors and single-use time-step checks', () => {
 const secret='GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
 for(const [seconds,code] of [[59,'94287082'],[1111111109,'07081804'],[1111111111,'14050471'],[1234567890,'89005924'],[2000000000,'69279037'],[20000000000,'65353130']] as const) assert.equal(totp(secret,seconds*1000,8),code);
 const at=1234567890000;const code=totp(secret,at);
 assert.equal(matchingCounter(secret,code,at,-1),Math.floor(at/30000));
 assert.equal(matchingCounter(secret,code,at,Math.floor(at/30000)),null);
 assert.equal(matchingCounter(secret,code,at+120000,-1),null);
});

import { mkdtemp,readFile,rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { beginEnrollment,confirmEnrollment } from '../api/src/admin-identity.ts';
import { createApp } from '../api/src/app.ts';
import { newTotpSecret } from '../api/src/totp.ts';

test('CLI enrollment activates only after proof, rejects passwords/replay, persists limits and revokes old sessions on reset', async t => {
 const directory=await mkdtemp(join(tmpdir(),'ams-totp-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 let now=1794400000000;const origin='https://ams.opt.work';
 const artifact=beginEnrollment(directory,'operator',false,now);const secret=JSON.parse(await readFile(artifact,'utf8')).secret as string;
 assert.throws(()=>confirmEnrollment(directory,'not-a-code',now));
 let app=await createApp({directory,enrollmentKey:newTotpSecret(),origin,now:()=>now});
 const login=(body:Record<string,string>)=>app.inject({method:'POST',url:'/api/v1/admin/login',headers:{origin},payload:body});
 assert.equal((await login({username:'operator',code:totp(secret,now)})).statusCode,401);
 confirmEnrollment(directory,totp(secret,now),now);now+=30000;
 const responses=await Promise.all([login({username:'operator',code:totp(secret,now)}),login({username:'operator',code:totp(secret,now)})]);
 assert.deepEqual(responses.map(r=>r.statusCode).sort(),[200,401]);const good=responses.find(r=>r.statusCode===200)!;const cookie=String(good.headers['set-cookie']).split(';')[0];
 assert.equal((await login({username:'operator',password:'fixed-password'})).statusCode,400);
 const invalid=totp(secret,now)==='000000'?'111111':'000000';
 for(let i=0;i<4;i++)assert.equal((await login({username:'operator',code:invalid})).statusCode,401);
 await app.close();app=await createApp({directory,enrollmentKey:newTotpSecret(),origin,now:()=>now});
 assert.equal((await login({username:'operator',code:invalid})).statusCode,429);
 const reset=beginEnrollment(directory,'operator',true,now);const replacement=JSON.parse(await readFile(reset,'utf8')).secret as string;
 confirmEnrollment(directory,totp(replacement,now),now);
 assert.equal((await app.inject({url:'/api/v1/admin/session',headers:{cookie}})).statusCode,401);
 now+=6*60000;assert.equal((await login({username:'operator',code:totp(replacement,now)})).statusCode,200);
 await app.close();
});

import { spawnSync } from 'node:child_process';
import { Store } from '../api/src/db.ts';
import Database from 'better-sqlite3';
test('prepare and an invalid activation code do not migrate or replace a legacy account', async t => {
 const directory=await mkdtemp(join(tmpdir(),'ams-totp-legacy-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 const store=new Store(directory);store.put('admins','administrator','',{username:'operator',legacy:true});store.db.exec('DROP TABLE auth_attempts');store.db.pragma('user_version=3');store.close();
 const run=(action:string,code?:string)=>spawnSync(process.execPath,['api/src/admin-cli.ts',action],{cwd:join(import.meta.dirname,'..'),env:{...process.env,AMS_DATA_DIR:directory,AMS_ADMIN_USER:'operator',...(code?{AMS_TOTP_CODE:code}:{})},encoding:'utf8'});
 assert.equal(run('prepare').status,0);assert.equal(run('prepare').status,1);const artifact=JSON.parse(await readFile(join(directory,'totp-enrollment.json'),'utf8')) as {secret:string};
 const wrong=totp(artifact.secret,Date.now())==='000000'?'111111':'000000';assert.equal(run('activate',wrong).status,1);
 const db=new Database(join(directory,'manager.sqlite'),{readonly:true});try{assert.equal(db.pragma('user_version',{simple:true}),3);assert.deepEqual(JSON.parse((db.prepare("SELECT data FROM admins WHERE id='administrator'").get() as {data:string}).data),{username:'operator',legacy:true});}finally{db.close();}
});

import { backupData } from '../api/src/backup.ts';
import { provisionAdmin } from '../api/src/admin-identity.ts';
test('missing TOTP encryption key fails startup and backup without silently regenerating it', async t => {
 const directory=await mkdtemp(join(tmpdir(),'ams-key-loss-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 provisionAdmin(directory,'operator',newTotpSecret());await rm(join(directory,'auth.key'));
 await assert.rejects(createApp({directory,enrollmentKey:newTotpSecret(),origin:'https://ams.opt.work'}));
 await assert.rejects(backupData(directory,join(directory,'backups')));
 await assert.rejects(readFile(join(directory,'auth.key')));
});
