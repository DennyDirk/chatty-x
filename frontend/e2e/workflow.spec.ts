import { test, expect, type APIRequestContext } from '@playwright/test';
import { createHmac } from 'node:crypto';

function otp(secret: string) {
  const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';let bits='';
  for(const letter of secret)bits+=alphabet.indexOf(letter).toString(2).padStart(5,'0');
  const key=Buffer.from(bits.match(/.{8}/g)!.map(byte=>Number.parseInt(byte,2)));
  const counter=Buffer.alloc(8);counter.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));
  const hash=createHmac('sha1',key).update(counter).digest();
  return ((hash.readUInt32BE(hash[19]&15)&0x7fffffff)%1000000).toString().padStart(6,'0');
}
async function mutate(request: APIRequestContext,path:string,method:string,data?:unknown) {
  const csrf=await (await request.get('/api/v1/identity/csrf')).json();
  const result=await request.fetch('/api/v1'+path,{method,data,headers:{[csrf.headerName]:csrf.token}});
  expect(result.ok(),`${method} ${path}: ${result.status()} ${await result.text()}`).toBeTruthy();
  const text=await result.text();return text?JSON.parse(text):undefined;
}

test('owner setup, account selection, one reply per burst, manual takeover and mobile layout',async({page})=>{
  await expect.poll(async()=>{try{return (await page.request.get('/health')).status();}catch{return 0;}},{timeout:60000}).toBe(200);
  const status=await(await page.request.get('/api/v1/identity/status')).json();
  expect(status.setupRequired,'Use a fresh isolated e2e database').toBe(true);
  expect((await page.request.get('/api/v1/conversations')).status()).toBe(401);
  expect((await page.request.post('/api/v1/stop')).status()).toBe(403);
  await page.goto('/');
  await page.getByLabel('Ключ первоначальной настройки').fill('synthetic-bootstrap-token-for-tests-only');
  await page.getByRole('button',{name:'Настроить защиту'}).click();
  const secret=await page.locator('.totp-secret').innerText();
  await page.getByLabel('Имя владельца').fill('owner');
  await page.getByLabel('Пароль',{exact:false}).fill('synthetic-long-password');
  await page.getByLabel('Код из приложения').fill(otp(secret));
  await page.getByRole('button',{name:'Создать пространство'}).click();
  await expect(page.getByRole('heading',{name:/^Диалоги/})).toBeVisible();
  const connection=await mutate(page.request,'/connections','POST',{name:'Тестовый аккаунт',adapter:'fake'});
  await mutate(page.request,`/connections/${connection.id}/authorization`,'POST',{method:'test'});
  await mutate(page.request,`/connections/${connection.id}/enabled`,'PUT',{enabled:true});
  const chats=await(await page.request.get('/api/v1/conversations')).json();
  const chat=chats.find((c:{externalId:string})=>c.externalId==='1001');
  await mutate(page.request,`/conversations/${chat.id}/selection`,'PUT',{selected:true});
  await expect.poll(async()=> (await(await page.request.get(`/api/v1/conversations/${chat.id}`)).json()).imported).toBe(true);
  await page.reload();
  await page.getByRole('button',{name:/Аня · тестовый чат/}).click();
  await expect(page.getByText(/История загружена:/)).toBeVisible();
  const reimport = page.waitForResponse(response => response.url().endsWith(`/conversations/${chat.id}/import`) && response.request().method() === 'POST');
  await page.getByRole('button',{name:'Загрузить заново'}).click();
  expect((await reimport).ok()).toBe(true);
  await expect.poll(async()=> (await(await page.request.get(`/api/v1/conversations/${chat.id}`)).json()).imported).toBe(true);
  expect((await(await page.request.get(`/api/v1/conversations/${chat.id}`)).json()).mode).toBe('PAUSED');
  expect((await(await page.request.get(`/api/v1/conversations/${chat.id}/messages`)).json())).toHaveLength(0);
  const settings=await(await page.request.get('/api/v1/settings')).json();
  await mutate(page.request,'/settings','PUT',{version:settings.version,body:{...settings.body,enabled:true}});
  await mutate(page.request,`/conversations/${chat.id}/control/resume`,'POST');
  for(const text of ['Привет','Ты как?','Я сегодня','наконец закрыл','тот проект'])await mutate(page.request,`/conversations/${chat.id}/simulate`,'POST',{text,outgoing:false});
  await expect.poll(async()=> (await(await page.request.get(`/api/v1/conversations/${chat.id}/messages`)).json()).filter((m:{direction:string})=>m.direction==='OUT').length,{timeout:25000}).toBe(1);
  await page.reload();
  await page.getByRole('button',{name:/Аня · тестовый чат/}).click();
  await expect(page.locator('.messages').getByText('Слышу тебя 🙂 Расскажешь чуть подробнее?')).toBeVisible();
  await mutate(page.request,'/stop','POST');
  await mutate(page.request,`/connections/${connection.id}/enabled`,'PUT',{enabled:false});
  await expect(page.getByText('Автоответы выключены в общих настройках.')).toBeVisible();
  await expect(page.getByText('Автоматизация аккаунта выключена.')).toBeVisible();
  expect(await(await page.request.get('/api/v1/runtime')).json()).toEqual({workersEnabled:true,automationEnabled:false,modelConfigured:false,demo:true});
  await page.getByLabel('Ваш ответ').fill('Сейчас отвечу сам');
  await page.getByRole('button',{name:'Отправить сообщение',exact:true}).click();
  await expect(page.getByRole('button',{name:'Возобновить'})).toBeVisible();
  await expect(page.locator('.messages').getByText('Сейчас отвечу сам',{exact:true})).toBeVisible();
  await expect.poll(async()=> (await(await page.request.get(`/api/v1/conversations/${chat.id}/messages`)).json()).filter((m:{source:string})=>m.source==='WEB_OWNER').length).toBe(1);
  await mutate(page.request,`/conversations/${chat.id}/simulate`,'POST',{text:'А ещё вопрос',outgoing:false});
  const state=await(await page.request.get(`/api/v1/conversations/${chat.id}`)).json();expect(state.mode).toBe('PAUSED');
  await page.screenshot({path:'test-results/desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByLabel('Ваш ответ')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/mobile.png',fullPage:true});
});
