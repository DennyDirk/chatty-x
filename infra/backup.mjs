import {spawn} from 'node:child_process';
import {createReadStream,createWriteStream} from 'node:fs';
import {appendFile,mkdir,mkdtemp,open,readFile,readdir,realpath,rm,stat,writeFile} from 'node:fs/promises';
import {createCipheriv,createDecipheriv,createHash,hkdfSync,randomBytes} from 'node:crypto';
import {pipeline} from 'node:stream/promises';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../',import.meta.url));
const args=process.argv.slice(2), action=args[0];
function option(name,fallback){const n=args.indexOf(name);return n>=0?args[n+1]:fallback;}
const project=option('--project','chatty-x');
if(!/^[a-z][a-z0-9-]{1,60}$/.test(project))throw new Error('Invalid project name');
const databaseService=option('--database-service','db'),backendService=option('--backend-service','backend');
if(!['db','db-e2e'].includes(databaseService)||!['backend','backend-e2e'].includes(backendService))throw new Error('Invalid backup service');
const base=['compose','-p',project];
function processDocker(arguments_,input){
  const child=spawn('docker',[...base,...arguments_],{cwd:root,stdio:['pipe','pipe','pipe'],windowsHide:true});
  let error='';child.stderr.on('data',data=>{error+=data.toString();if(error.length>10000)error=error.slice(-10000);});
  const done=once(child,'close').then(([code])=>{if(code!==0)throw new Error(`Docker operation failed (${code}); inspect the selected service locally.`);});
  if(input)input.pipe(child.stdin);else child.stdin.end();
  return {child,done};
}
async function capture(arguments_,input){const {child,done}=processDocker(arguments_,input);const chunks=[];for await(const chunk of child.stdout)chunks.push(chunk);await done;return Buffer.concat(chunks);}
async function command(arguments_){await capture(arguments_);}
const config=JSON.parse((await capture(['config','--format','json'])).toString());
const master=Buffer.from(config.services[backendService].environment.MASTER_KEY,'base64');
if(master.length!==32)throw new Error('Invalid master key');
const key=Buffer.from(hkdfSync('sha256',master,Buffer.alloc(0),'chatty-x backup v1',32));
const fingerprint=createHash('sha256').update(master).digest('hex');
const database=config.services[databaseService].environment;
const user=database.POSTGRES_USER,name=database.POSTGRES_DB;
const execDb=['exec','-T',databaseService];
const backups=path.join(root,'backups');await mkdir(backups,{recursive:true,mode:0o700});

async function encrypted(arguments_,target){
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
  const output=createWriteStream(target,{flags:'wx',mode:0o600});output.write(Buffer.concat([Buffer.from('CHX1'),iv]));
  const {child,done}=processDocker(arguments_);
  await Promise.all([pipeline(child.stdout,cipher,output),done]);
  await appendFile(target,cipher.getAuthTag());
}
async function decrypted(source,target){
  const info=await stat(source);if(info.size<32)throw new Error('Invalid encrypted backup');
  const handle=await open(source,'r');const header=Buffer.alloc(16),tag=Buffer.alloc(16);
  try{await handle.read(header,0,16,0);await handle.read(tag,0,16,info.size-16);}finally{await handle.close();}
  if(header.subarray(0,4).toString()!=='CHX1')throw new Error('Invalid backup format');
  const cipher=createDecipheriv('aes-256-gcm',key,header.subarray(4));cipher.setAuthTag(tag);
  // Authenticate the complete backup before feeding any SQL or archive to the destination.
  await pipeline(createReadStream(source,{start:16,end:info.size-17}),cipher,createWriteStream(target,{flags:'wx',mode:0o600}));
}
async function safeRemove(directory){
  const resolved=await realpath(directory),parent=await realpath(backups);
  if(!resolved.startsWith(parent+path.sep)||path.dirname(resolved)!==parent)throw new Error('Refusing removal outside backup directory');
  await rm(resolved,{recursive:true,force:false});
}

if(action==='create'){
  const directory=path.join(backups,new Date().toISOString().replaceAll(':','-').replaceAll('.','-'));
  await mkdir(directory,{mode:0o700});
  await command(['stop',backendService]);
  try{
    await command(['run','--rm','-T','--no-deps','--entrypoint','mkdir',backendService,'-p','/data/attachments']);
    await encrypted([...execDb,'pg_dump','-U',user,'-d',name,'-Fc','--no-owner','--no-acl'],path.join(directory,'database.chx'));
    await encrypted(['run','--rm','-T','--no-deps','--entrypoint','tar',backendService,'-C','/data','-cf','-','attachments'],path.join(directory,'attachments.chx'));
    await writeFile(path.join(directory,'manifest.json'),JSON.stringify({format:1,createdAt:new Date().toISOString(),masterKeyFingerprint:fingerprint,telegramSessions:'reauthorize'},null,2),{mode:0o600});
  }finally{await command(['start',backendService]);}
  for(const entry of await readdir(backups,{withFileTypes:true})){
    if(!entry.isDirectory()||!/^\d{4}-\d{2}-\d{2}T/.test(entry.name))continue;
    const old=path.join(backups,entry.name);
    try{const manifest=JSON.parse(await readFile(path.join(old,'manifest.json'),'utf8'));if(manifest.format===1&&Date.parse(manifest.createdAt)<Date.now()-14*86400000)await safeRemove(old);}catch(error){if(error.code!=='ENOENT')throw error;}
  }
  console.log(`Encrypted backup created: ${directory}`);
}else if(action==='restore'){
  const directory=path.resolve(args[1]??'');
  const manifest=JSON.parse(await readFile(path.join(directory,'manifest.json'),'utf8'));
  if(manifest.format!==1||manifest.masterKeyFingerprint!==fingerprint)throw new Error('Use the original master key from a separate secure copy');
  await command(['up','-d','--wait',databaseService]);
  const tables=(await capture([...execDb,'psql','-U',user,'-d',name,'-Atc',"SELECT count(*) FROM pg_tables WHERE schemaname='public'"])).toString().trim();
  if(tables!=='0')throw new Error('Restore requires an empty database in a separate Compose project');
  await command(['stop',backendService]);
  const temporary=await mkdtemp(path.join(backups,'.restore-'));
  try{
    await decrypted(path.join(directory,'database.chx'),path.join(temporary,'database.dump'));
    await decrypted(path.join(directory,'attachments.chx'),path.join(temporary,'attachments.tar'));
    const listing=(await capture(['run','--rm','-T','--no-deps','--entrypoint','tar',backendService,'-tf','-'],createReadStream(path.join(temporary,'attachments.tar')))).toString().trim().split('\n');
    if(listing.some(line=>!/^attachments\/$|^attachments\/[0-9a-f-]+\.(jpg|ogg|wav)$/.test(line)))throw new Error('Unexpected archive path');
    const types=(await capture(['run','--rm','-T','--no-deps','--entrypoint','tar',backendService,'-tvf','-'],createReadStream(path.join(temporary,'attachments.tar')))).toString().trim().split('\n');
    if(types.some(line=>!['-','d'].includes(line[0])))throw new Error('Links and special files are not accepted');
    await capture([...execDb,'pg_restore','-U',user,'-d',name,'--no-owner','--no-acl','--exit-on-error'],createReadStream(path.join(temporary,'database.dump')));
    await capture([...execDb,'psql','-U',user,'-d',name,'-v','ON_ERROR_STOP=1'],createReadStream(path.join(root,'infra','restore-pause.sql')));
    await capture(['run','--rm','-T','--no-deps','--entrypoint','tar',backendService,'-C','/data','--no-same-owner','-xf','-'],createReadStream(path.join(temporary,'attachments.tar')));
    console.log('Restore completed. Automation is paused; Telegram requires login. Start backend and review before resuming.');
  }finally{await safeRemove(temporary);}
}else{throw new Error('Usage: node infra/backup.mjs create | restore BACKUP_DIRECTORY [--project NAME]');}
