const {chromium}=require(process.env.REPAIR_PLAYWRIGHT_MODULE||'playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const backend=process.argv[2];
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(backend||'')) throw Error('Isolated localhost backend required');
const dist=require('./browser-dist.cjs'),out=path.resolve(__dirname,'../target/repair-workflow-browser');
fs.mkdirSync(out,{recursive:true});
const server=http.createServer((req,res)=>{
 if(req.url.startsWith('/api/')){const proxy=http.request(new URL(req.url,backend),{method:req.method,headers:{...req.headers,host:new URL(backend).host}},up=>{res.writeHead(up.statusCode,up.headers);up.pipe(res)});proxy.on('error',()=>{res.writeHead(502);res.end()});req.pipe(proxy);return;}
 const url=new URL(req.url,'http://127.0.0.1:4877'),file=url.pathname.startsWith('/assets/')?path.join(dist,url.pathname):path.join(dist,'index.html');
 res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');try{res.end(fs.readFileSync(file))}catch{res.writeHead(404);res.end()}
});
let browser;
async function waitLayout(page){await page.waitForFunction(()=>{const s=document.querySelector('.repair-sidebar'),d=document.querySelector('.ant-drawer-open .ant-drawer-content-wrapper');return(innerWidth>=992||!s||s.getBoundingClientRect().width<1)&&(!d||d.getBoundingClientRect().right<=innerWidth+1)});}
async function choose(page,label,text){await page.getByLabel(label,{exact:true}).press('ArrowDown');await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(text,{exact:true}).click();}
async function signIn(page,email){await page.getByLabel('工作邮箱').fill(email);await page.getByLabel('密码',{exact:true}).fill('FixtureOnly3579!');await page.locator('button[type="submit"]').click();}
async function saveAction(page,name){await page.getByRole('button',{name,exact:true}).click();const dialog=page.getByRole('dialog',{name,exact:true});if(name==='审核通过')await choose(page,'处理方案','直接维修 · 无需采购');await dialog.getByRole('button',{name:'确认保存',exact:true}).click();await dialog.waitFor({state:'hidden'});}
(async()=>{
 await new Promise(resolve=>server.listen(4877,'127.0.0.1',resolve));browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();page.setDefaultTimeout(12000);
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:4877/tickets');await page.getByLabel('工作邮箱').fill('hq@repair.example');await page.getByLabel('密码',{exact:true}).fill('FixtureOnly3579!');await page.locator('button[type="submit"]').click();
 await page.getByRole('heading',{name:'工单工作台',exact:true}).waitFor();await page.getByRole('button',{name:'完整维修主线',exact:true}).waitFor();
 await page.screenshot({path:path.join(out,'tickets-desktop.png'),fullPage:true});
 await page.getByRole('button',{name:'完整维修主线',exact:true}).click();await page.getByRole('heading',{name:'完整维修主线',exact:true}).waitFor();
 await page.waitForFunction(()=>document.querySelector('.ant-drawer-open .ant-drawer-content-wrapper').getBoundingClientRect().right<=innerWidth+1);
 await page.getByText('已紧固风扇支架，连续运行稳定。',{exact:true}).waitFor();
 await page.screenshot({path:path.join(out,'ticket-detail-desktop.png'),fullPage:true});
 await page.locator('.ant-drawer-close').click();
 await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.querySelector('.repair-sidebar').getBoundingClientRect().width<1);
 await page.screenshot({path:path.join(out,'tickets-mobile.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 await page.setViewportSize({width:1440,height:1000});await page.getByRole('menuitem',{name:'设施管理'}).click();await page.getByRole('button',{name:'新增设施'}).click();
 await choose(page,'所属门店','测试门店 WORK-A');await page.getByLabel('设施编号',{exact:true}).fill('UI-FREEZER');await page.getByLabel('设施名称',{exact:true}).fill('前厅制冰机');await page.getByLabel('安装位置',{exact:true}).fill('前厅吧台');await page.getByLabel('原设施品牌（选填）').fill('合成设备品牌');await page.getByLabel('原设施型号（选填）').fill('ICE-UI01');
 const assetDialog=page.getByRole('dialog',{name:'新增设施台账',exact:true});await assetDialog.getByRole('button',{name:'保存',exact:true}).click();await assetDialog.waitFor({state:'hidden'});await page.getByText('UI-FREEZER',{exact:true}).waitFor();
 const managerContext=await browser.newContext({viewport:{width:390,height:844}}),manager=await managerContext.newPage();manager.setDefaultTimeout(12000);manager.on('pageerror',e=>errors.push(e.message));
 await manager.goto('http://127.0.0.1:4877/tickets/new');await signIn(manager,'a@workflow.example');await manager.getByRole('dialog',{name:'提交门店报修',exact:true}).waitFor();
 await choose(manager,'故障设施','前厅制冰机 · UI-FREEZER · 前厅吧台');await choose(manager,'报修分类','制冷设备');await manager.getByLabel('故障标题',{exact:true}).fill('浏览器制冰机报修');await manager.getByLabel('故障描述',{exact:true}).fill('制冰机停止制冰，已关闭电源并安排临时采购冰块，请检查供水与制冷组件。');await manager.getByLabel('联系电话',{exact:true}).fill('13000000001');
 const fixture={name:'synthetic.png',mimeType:'image/png',buffer:fs.readFileSync(path.join(out,'sample.png'))};await manager.getByLabel('选择维修图片',{exact:true}).setInputFiles(fixture);await manager.locator('.upload-preview').waitFor();
 await waitLayout(manager);await manager.screenshot({path:path.join(out,'new-report-mobile.png'),fullPage:true});
 await manager.getByRole('dialog',{name:'提交门店报修',exact:true}).getByRole('button',{name:'提交报修',exact:true}).click();await manager.waitForURL(/\/tickets\/\d+$/);const ticketId=new URL(manager.url()).pathname.split('/').pop();await manager.getByRole('heading',{name:'浏览器制冰机报修',exact:true}).waitFor();
 await page.goto('http://127.0.0.1:4877/tickets/'+ticketId);await page.getByRole('heading',{name:'浏览器制冰机报修',exact:true}).waitFor();await saveAction(page,'审核通过');
 await page.getByRole('button',{name:'安排工程师',exact:true}).click();await choose(page,'维修工程师','测试成员 · one@workflow.example');const assignDialog=page.getByRole('dialog',{name:'安排工程师',exact:true});await assignDialog.getByRole('button',{name:'确认保存',exact:true}).click();await assignDialog.waitFor({state:'hidden'});
 const engineerContext=await browser.newContext({viewport:{width:390,height:844}}),engineer=await engineerContext.newPage();engineer.setDefaultTimeout(12000);engineer.on('pageerror',e=>errors.push(e.message));await engineer.goto('http://127.0.0.1:4877/tickets/'+ticketId);await signIn(engineer,'one@workflow.example');await engineer.getByRole('heading',{name:'浏览器制冰机报修',exact:true}).waitFor();await saveAction(engineer,'确认接单');await saveAction(engineer,'开始维修');
 await engineer.getByRole('button',{name:'提交维修结果',exact:true}).click();await engineer.getByLabel('维修结果说明',{exact:true}).fill('已清理进水滤网并更换密封件，连续制冰测试正常。');await engineer.getByLabel('选择维修图片',{exact:true}).setInputFiles(fixture);await engineer.locator('.upload-preview').waitFor();const resultDialog=engineer.getByRole('dialog',{name:'提交维修结果',exact:true});await resultDialog.getByRole('button',{name:'确认保存',exact:true}).click();await resultDialog.waitFor({state:'hidden'});
 await manager.locator('.ant-drawer-open').getByRole('button',{name:'刷新'}).click();await saveAction(manager,'验收通过');await manager.getByText(/WX\d+ · 已完成/).waitFor();await waitLayout(manager);await manager.screenshot({path:path.join(out,'completed-report-mobile.png'),fullPage:true});
 // Switch accounts on a HQ-only page; a store member must land directly on the authorized workbench.
 await page.goto('http://127.0.0.1:4877/hq/members');await page.getByRole('heading',{name:'成员与权限',exact:true}).waitFor();
 await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();assert.equal(new URL(page.url()).pathname,'/');
 await page.goto('http://127.0.0.1:4877/hq/members');const forbiddenRequests=[];page.on('request',request=>{if(new URL(request.url()).pathname==='/api/v1/repair/members')forbiddenRequests.push(request.url());});
 await signIn(page,'a@workflow.example');await page.waitForURL('http://127.0.0.1:4877/');await page.getByRole('heading',{name:'欢迎，测试成员',exact:true}).waitFor();await page.getByRole('button',{name:'提交报修',exact:true}).waitFor();assert.deepEqual(forbiddenRequests,[]);
 await page.goto('http://127.0.0.1:4877/hq/reports');await page.waitForURL('http://127.0.0.1:4877/');assert.equal(await page.getByText('当前身份无法访问此页面',{exact:true}).count(),0);
 await page.setViewportSize({width:390,height:844});await waitLayout(page);await page.screenshot({path:path.join(out,'store-role-landing-mobile.png'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 await engineer.goto('http://127.0.0.1:4877/stores');await engineer.waitForURL('http://127.0.0.1:4877/');await engineer.getByRole('heading',{name:'欢迎，测试成员',exact:true}).waitFor();
 assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,scope:'real API full browser repair flow, private images, desktop and mobile',pageErrors:errors}));
})().catch(async e=>{console.error(e.stack);if(browser)for(const context of browser.contexts())for(const page of context.pages()){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'failure.txt'),await page.locator('body').innerText().catch(()=>''))}process.exitCode=1}).finally(async()=>{if(browser)await browser.close();server.close()});
