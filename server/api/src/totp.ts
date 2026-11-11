import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export function newTotpSecret(): string {
 const bytes=randomBytes(20);let bits=0,value=0,result='';
 for(const byte of bytes) {value=(value<<8)|byte;bits+=8;while(bits>=5){bits-=5;result+=alphabet[(value>>>bits)&31];}value&=(1<<bits)-1;}
 if(bits)result+=alphabet[(value<<(5-bits))&31];return result;
}
function decode(secret:string):Buffer {
 if(!/^[A-Z2-7]{32,128}$/.test(secret))throw new Error('Invalid TOTP secret');
 let bits=0,value=0;const bytes:number[]=[];
 for(const c of secret){value=(value<<5)|alphabet.indexOf(c);bits+=5;if(bits>=8){bits-=8;bytes.push((value>>>bits)&255);}value&=(1<<bits)-1;}
 return Buffer.from(bytes);
}
export function totp(secret:string,atMs:number,digits:6|8=6):string {
 if(!Number.isSafeInteger(atMs)||atMs<0)throw new Error('Invalid TOTP time');
 const counter=Buffer.alloc(8);counter.writeBigUInt64BE(BigInt(Math.floor(atMs/30000)));
 const hash=createHmac('sha1',decode(secret)).update(counter).digest();const offset=hash[hash.length-1]!&15;
 return ((hash.readUInt32BE(offset)&0x7fffffff)%10**digits).toString().padStart(digits,'0');
}
export function matchingCounter(secret:string,code:string,atMs:number,lastCounter:number):number|null {
 if(!/^\d{6}$/.test(code))return null;
 const current=Math.floor(atMs/30000);let matched:number|null=null;
 for(const step of [current-1,current,current+1]) {
  if(step<0)continue;
  const same=timingSafeEqual(Buffer.from(totp(secret,step*30000)),Buffer.from(code));
  if(same&&step>lastCounter)matched=Math.max(matched??-1,step);
 }
 return matched;
}
