const {chromium}=require(process.env.REPAIR_PLAYWRIGHT_MODULE||'playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const backend=process.argv[2];if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(backend||''))throw Error('Isolated localhost backend required');
const dist=require('./browser-dist.cjs'),out=path.resolve(__dirname,'../target/repair-session-browser');fs.mkdirSync(out,{recursive:true});
const server=http.createServer((req,res)=>{
 if(req.url.startsWith('/api/')){const proxy=http.request(new URL(req.url,backend),{method:req.method,headers:{...req.headers,host:new URL(backend).host}},up=>{res.writeHead(up.statusCode,up.headers);up.pipe(res)});proxy.on('error',()=>{res.writeHead(502);res.end()});req.pipe(proxy);return;}
 const url=new URL(req.url,'http://127.0.0.1:4877'),file=url.pathname.startsWith('/assets/')?path.join(dist,url.pathname):path.join(dist,'index.html');
 res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');try{res.end(fs.readFileSync(file));}catch{res.writeHead(404);res.end();}
});
let browser;
(async()=>{
 await new Promise(resolve=>server.listen(4877,'127.0.0.1',resolve));browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];page.setDefaultTimeout(12000);page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:4877');await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();
 await page.getByLabel('工作邮箱').fill('hq@repair.example');await page.getByLabel('密码',{exact:true}).fill('FixtureOnly3579!');
 const responsePromise=page.waitForResponse(r=>r.url().endsWith('/api/v1/auth/login')&&r.request().method()==='POST');await page.locator('button[type="submit"]').click();
 const payload=await(await responsePromise).json();assert.equal(Object.hasOwn(payload.token,'refreshToken'),false);const access=payload.token.accessToken;
 await page.getByRole('heading',{name:'欢迎，测试总部',exact:true}).waitFor();await page.getByRole('button',{name:'编辑工作资料',exact:true}).click();await page.getByRole('dialog',{name:'完善你的工作资料'}).waitFor();await page.getByLabel('显示姓名').fill('安全回归总部');await page.getByRole('button',{name:'保存并进入',exact:true}).click();
 await page.getByRole('heading',{name:'欢迎，安全回归总部',exact:true}).waitFor();
 const cookies=await context.cookies();const refresh=cookies.find(x=>x.name==='repair_refresh_local');assert.ok(refresh);assert.equal(refresh.httpOnly,true);assert.equal(refresh.sameSite,'Lax');
 assert.deepEqual(await page.evaluate(()=>({old:localStorage.getItem('repair_auth_tokens'),session:sessionStorage.getItem('repair_auth_tokens'),cookie:document.cookie.includes('repair_refresh')})),{old:null,session:null,cookie:false});
 await page.reload();await page.getByRole('heading',{name:'欢迎，安全回归总部',exact:true}).waitFor();
 const second=await context.newPage();await second.goto('http://127.0.0.1:4877');await second.getByRole('heading',{name:'欢迎，安全回归总部',exact:true}).waitFor();
 // A failed network request must be shown as a failed logout, not reported as a completed revocation.
 await page.route('**/api/v1/auth/logout',route=>route.abort());await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByText(/退出未完成，请检查网络后重试/).waitFor();
 assert.equal((await context.request.get(backend+'/api/v1/repair/session',{headers:{Authorization:'Bearer '+access}})).status(),200);
 await page.unroute('**/api/v1/auth/logout');await page.getByRole('button',{name:'返回登录',exact:true}).click();await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();
 await second.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();
 assert.equal((await context.request.get(backend+'/api/v1/repair/session',{headers:{Authorization:'Bearer '+access}})).status(),401);
 assert.equal((await context.cookies()).some(x=>x.name==='repair_refresh_local'),false);
 await page.reload();await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();await page.screenshot({path:path.join(out,'signed-out-desktop.png'),fullPage:true});
 assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,scope:'HttpOnly refresh, memory-only access, reload restoration, cross-tab logout, logout failure feedback, immediate access revocation'}));
})().catch(error=>{console.error(error);process.exitCode=1}).finally(async()=>{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));});
