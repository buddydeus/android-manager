// Read-only deployment probe: legacy accounts must not trigger a storage migration.
import Database from 'better-sqlite3';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { tablesForVersion } from './db.ts';
import { identitySchema,decryptSecret } from './admin-identity.ts';
try {
 const directory=process.env.AMS_DATA_DIR;if(!directory)throw new Error('Missing data directory');
 const file=join(directory,'manager.sqlite');
 if(!existsSync(file))process.stdout.write('uninitialized\n');
 else {
  const db=new Database(file,{readonly:true,fileMustExist:true});try {
   const version=db.pragma('user_version',{simple:true});if(![1,2,3,4].includes(Number(version)))throw new Error('Unknown storage');
   for(const table of tablesForVersion(Number(version))){const cols=db.prepare(`PRAGMA table_info(${table})`).all() as {name:string}[];if(cols.length!==3||!['id','deviceId','data'].every(name=>cols.some(c=>c.name===name)))throw new Error('Invalid storage schema');}
   const row=db.prepare("SELECT data FROM admins WHERE id='administrator'").get() as {data:string}|undefined;
   if(row){const value=JSON.parse(row.data) as Record<string,unknown>;
    if(value.kind==='TOTP'){const admin=identitySchema.parse(value);decryptSecret(directory,admin.encryptedSecret);process.stdout.write('initialized\n');}
    else {if(value.kdf!=='scrypt'||value.kdfVersion!==1)throw new Error('Unknown identity');process.stdout.write('legacy\n');}
   }else process.stdout.write(db.prepare("SELECT 1 FROM admins WHERE id='totp-pending'").get()?'pending\n':'uninitialized\n');
  }finally{db.close();}
 }
}catch{process.stderr.write('部署检查失败：请检查身份、密钥和存储版本，禁止清库重试\n');process.exitCode=1;}
