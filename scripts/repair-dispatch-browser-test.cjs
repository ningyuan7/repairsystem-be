const {chromium}=require(process.env.REPAIR_PLAYWRIGHT_MODULE||'playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const backend=process.argv[2];
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(backend||''))throw Error('Isolated localhost backend required');
const dist=require('./browser-dist.cjs'),out=path.resolve(__dirname,'../target/repair-dispatch-browser');
fs.mkdirSync(out,{recursive:true});
const server=http.createServer((req,res)=>{
 if(req.url.startsWith('/api/')){const proxy=http.request(new URL(req.url,backend),{method:req.method,headers:{...req.headers,host:new URL(backend).host}},up=>{res.writeHead(up.statusCode,up.headers);up.pipe(res)});proxy.on('error',()=>{res.writeHead(502);res.end()});req.pipe(proxy);return;}
 const url=new URL(req.url,'http://127.0.0.1:4877'),file=url.pathname.startsWith('/assets/')?path.join(dist,url.pathname):path.join(dist,'index.html');
 res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');try{res.end(fs.readFileSync(file))}catch{res.writeHead(404);res.end()}
});
let browser;
async function settled(page){await page.evaluate(async()=>{await Promise.all(document.getAnimations().filter(a=>a.effect?.getComputedTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{})))});}
(async()=>{
 await new Promise(resolve=>server.listen(4877,'127.0.0.1',resolve));browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1100}}),errors=[];page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:4877/');await page.getByLabel('工作邮箱',{exact:true}).fill('hq@repair.example');await page.getByLabel('密码',{exact:true}).fill('FixtureOnly3579!');await page.locator('button[type=submit]').click();
 const panel=page.getByRole('region',{name:'工程师派单一览'});await panel.getByText('陈工',{exact:true}).waitFor();
 const idle=panel.getByRole('row').filter({hasText:'刘工（制冷设备与门店电气维护）'});assert.equal(await idle.getByRole('button',{name:/查看.*派单/}).isDisabled(),true);
 await page.screenshot({path:path.join(out,'dispatch-desktop.png'),fullPage:true});
 await panel.getByRole('button',{name:'查看陈工的派单',exact:true}).click();
 const drawer=page.getByRole('dialog',{name:'陈工 · 当前派单',exact:true});await drawer.getByRole('button',{name:'待接单：冷柜温控器需要排查',exact:true}).waitFor();
 assert.equal(await drawer.getByText('已完成任务不进入当前派单',{exact:true}).count(),0);await settled(page);await page.screenshot({path:path.join(out,'dispatch-drawer-desktop.png'),fullPage:true});
 await drawer.getByRole('combobox',{name:'筛选工程师工单状态',exact:true}).press('ArrowDown');await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('维修中',{exact:true}).click();
 await drawer.getByRole('button',{name:'维修中：门店后厨冷柜持续高温，请检查温控器与线路连接',exact:true}).waitFor();await drawer.getByText('共 1 张工单',{exact:true}).waitFor();
 await page.setViewportSize({width:390,height:844});await settled(page);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(out,'dispatch-drawer-mobile.png'),fullPage:true});
 await drawer.getByRole('button',{name:'维修中：门店后厨冷柜持续高温，请检查温控器与线路连接',exact:true}).click();await page.waitForURL(/\/tickets\/\d+$/);await page.getByRole('dialog').waitFor();
 await page.goto('http://127.0.0.1:4877/');await panel.getByText('陈工',{exact:true}).waitFor();await settled(page);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(out,'dispatch-mobile.png'),fullPage:true});
 await panel.getByRole('button',{name:'去派单',exact:true}).click();await page.waitForURL('**/tickets?status=READY_FOR_DISPATCH');await page.getByRole('combobox',{name:'筛选工单状态',exact:true}).waitFor();
 const other=await browser.newPage();await other.goto('http://127.0.0.1:4877/');await other.getByLabel('工作邮箱',{exact:true}).fill('b@workflow.example');await other.getByLabel('密码',{exact:true}).fill('FixtureOnly3579!');await other.locator('button[type=submit]').click();await other.getByRole('heading',{name:'欢迎，测试成员',exact:true}).waitFor();assert.equal(await other.getByRole('region',{name:'工程师派单一览'}).count(),0);
 assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,scope:'HQ real dispatch counts, idle/disabled rows, filtered drawer, original ticket/dispatch navigation, role isolation, desktop/mobile',pageErrors:errors}));
})().catch(async e=>{console.error(e.stack);if(browser)for(const context of browser.contexts())for(const page of context.pages()){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'failure.txt'),await page.locator('body').innerText().catch(()=>''))}process.exitCode=1}).finally(async()=>{if(browser)await browser.close();server.close()});
