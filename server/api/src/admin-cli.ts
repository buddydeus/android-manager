import { beginEnrollment,confirmEnrollment } from './admin-identity.ts';
import { Store } from './db.ts';
import { matchingCounter,newTotpSecret } from './totp.ts';
import { readFileSync,writeFileSync,existsSync,mkdirSync,unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline/promises';
try {
 const directory=process.env.AMS_DATA_DIR;const action=process.argv[2];if(!directory)throw new Error('Missing data directory');
 if(action==='prepare') {
  const username=process.env.AMS_ADMIN_USER;if(!username||username.length>100)throw new Error('Missing username');
  mkdirSync(directory,{recursive:true,mode:0o700});const secret=newTotpSecret();const path=join(directory,'totp-enrollment.json');
  writeFileSync(path,JSON.stringify({username,secret,uri:`otpauth://totp/${encodeURIComponent('AMS:'+username)}?secret=${secret}&issuer=AMS&algorithm=SHA1&digits=6&period=30`}),{mode:0o600,flag:'wx'});
  process.stdout.write(`待绑定资料已保存至${path}，尚未改变数据库或现有登录。完成添加后运行init和confirm。\n`);
 }else if(action==='init'||action==='reset') {
  const username=process.env.AMS_ADMIN_USER;if(!username)throw new Error('Missing username');
  const prepared=join(directory,'totp-enrollment.json');const secret=existsSync(prepared)?JSON.parse(readFileSync(prepared,'utf8')).secret as string:undefined;
  const path=beginEnrollment(directory,username,action==='reset',Date.now(),secret);process.stdout.write(`绑定资料已写入权限600私有文件：${path}。添加到身份验证器后运行confirm；不要将文件上传或发送到聊天。\n`);
 }else if(action==='activate') {
  const artifact=JSON.parse(readFileSync(join(directory,'totp-enrollment.json'),'utf8')) as {username:string;secret:string};
  let code=process.env.AMS_TOTP_CODE;if(!code){const terminal=createInterface({input:process.stdin,output:process.stdout});try{code=await terminal.question('输入身份验证器当前6位动态码：');}finally{terminal.close();}}
  const now=Date.now();if(matchingCounter(artifact.secret,code.trim(),now,-1)===null)throw new Error('Invalid code');
  beginEnrollment(directory,artifact.username,false,now,artifact.secret);confirmEnrollment(directory,code.trim(),now);unlinkSync(join(directory,'totp-enrollment.json'));
  process.stdout.write('TOTP已启用，固定密码和旧会话已停用；请使用下一个动态码登录。\n');
 }else if(action==='confirm') {
  let code=process.env.AMS_TOTP_CODE;
  if(!code){const terminal=createInterface({input:process.stdin,output:process.stdout});try{code=await terminal.question('输入身份验证器当前6位动态码：');}finally{terminal.close();}}
  confirmEnrollment(directory,code.trim());try{unlinkSync(join(directory,'totp-enrollment.json'));}catch(error){if(!(error instanceof Error&&'code' in error&&error.code==='ENOENT'))throw error;}
  process.stdout.write('TOTP已启用，固定密码和全部旧会话已失效。请等待下一个动态码再登录。\n');
 }else if(action==='cancel') {
  const store=new Store(directory);try{store.delete('admins','totp-pending');}finally{store.close();}try{unlinkSync(join(directory,'totp-enrollment.json'));}catch(error){if(!(error instanceof Error&&'code' in error&&error.code==='ENOENT'))throw error;}process.stdout.write('已取消待确认绑定，现有身份不变。\n');
 }else throw new Error('Use init, confirm, reset or cancel');
}catch {process.stderr.write('TOTP管理操作失败：请检查命令、账号、绑定状态、验证码和私有密钥；原有身份不会自动重置。\n');process.exitCode=1;}
