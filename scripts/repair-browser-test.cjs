/* Run against the isolated server owned by RepairIdentityIntegrationTest. No API mocks. */
const { chromium } = require(process.env.REPAIR_PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const backend = process.argv[2];
if (!backend || !/^http:\/\/127\.0\.0\.1:\d+$/.test(backend)) throw Error('An isolated localhost backend is required');
const dist = require('./browser-dist.cjs');
const out = path.resolve(__dirname, '../target/repair-browser');
fs.mkdirSync(out, { recursive: true });
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const upstream = http.request(new URL(req.url, backend), { method: req.method, headers: { ...req.headers, host: new URL(backend).host } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.on('error', () => { res.writeHead(502); res.end(); }); req.pipe(upstream); return;
  }
  const url = new URL(req.url, 'http://127.0.0.1:4877');
  const file = url.pathname.startsWith('/assets/') ? path.join(dist, path.basename(url.pathname) ? url.pathname : '') : path.join(dist, 'index.html');
  res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  try { res.end(fs.readFileSync(file)); } catch { res.writeHead(404); res.end(); }
});
const results = [];
let browser;
async function login(page, email, password) {
  await page.getByLabel('工作邮箱').fill(email);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.locator('button[type="submit"]').click();
}
async function screenshot(page, name) {
  await page.waitForFunction(() => {
    const sidebar = document.querySelector('.repair-sidebar');
    return window.innerWidth >= 992 || !sidebar || sidebar.getBoundingClientRect().width < 1;
  });
  await page.screenshot({ path: path.join(out, name + '.png'), fullPage: true });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  assert.equal(overflow, false, name + ' must not overflow the viewport');
}
(async () => {
  await new Promise(resolve => server.listen(4877, '127.0.0.1', resolve));
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:4877');
  await page.getByRole('heading', { name: '登录工作台' }).waitFor();
  await screenshot(page, 'login-desktop');
  await login(page, 'hq@repair.example', 'wrong-password');
  await page.getByRole('alert').filter({ hasText: '登录凭证错误' }).waitFor();
  await page.getByRole('button', { name: '找回密码', exact: true }).click();
  const resetDialog = page.getByRole('dialog', { name: '找回登录密码', exact: true });
  await resetDialog.getByLabel('工作邮箱', { exact: true }).fill('hq@repair.example');
  await resetDialog.getByRole('button', { name: '发送验证码', exact: true }).click();
  await resetDialog.getByText('邮件服务尚未配置，请使用密码登录或联系总部管理员', { exact: true }).waitFor();
  await resetDialog.locator('.ant-modal-close').click();
  await resetDialog.waitFor({ state: 'hidden' });
  await page.getByLabel('密码', { exact: true }).fill('FixtureOnly3579!');
  await page.locator('button[type="submit"]').click();
  await page.getByRole('dialog', { name: '完善你的工作资料' }).waitFor();
  assert.equal(await page.getByRole('heading', { name: '欢迎，' }).count(), 0);
  await page.getByLabel('显示姓名').fill('华东总部 · 运营管理');
  await page.getByRole('button', { name: '保存并进入' }).click();
  await page.getByRole('dialog', { name: '完善你的工作资料' }).waitFor({ state: 'hidden' });
  await page.getByRole('heading', { name: '欢迎，华东总部 · 运营管理' }).waitFor();
  results.push('Password error feedback and profile gate before workbench');
  await page.getByRole('button', { name: '编辑工作资料', exact: true }).click();
  await page.getByLabel('联系电话', { exact: true }).fill('13000000000');
  await page.getByRole('button', { name: '保存并进入', exact: true }).click();
  await page.getByRole('dialog', { name: '完善你的工作资料' }).waitFor({ state: 'hidden' });
  results.push('Password reset unavailable feedback without email, editable work profile');
  await screenshot(page, 'workbench-desktop');

  await page.getByRole('menuitem', { name: '门店管理' }).click();
  await page.getByRole('button', { name: '新增门店' }).click();
  await page.getByLabel('门店编号').fill('SH-BROWSER');
  await page.getByLabel('门店名称').fill('上海徐汇滨江旗舰店 · 地下一层及外摆区域设施');
  await page.getByLabel('所属区域').fill('华东 · 上海');
  await page.getByLabel('详细地址').fill('上海市徐汇区合成测试地址 100 号（自动化合成数据）');
  await page.getByRole('button', { name: /^保\s*存$/ }).click();
  await page.getByRole('dialog', { name: '新增门店', exact: true }).waitFor({ state: 'hidden' });
  await page.getByRole('row').filter({ hasText: 'SH-BROWSER' }).waitFor();
  await screenshot(page, 'stores-desktop');
  results.push('Store creation with real backend, long Chinese title, missing address states');

  await page.getByRole('menuitem', { name: '成员与权限' }).click();
  await page.getByRole('button', { name: '邀请成员' }).click();
  await page.getByLabel('邀请邮箱').fill('browser-engineer@repair.example');
  await page.getByLabel('业务角色').press('ArrowDown');
  await page.getByTitle('维修工程师', { exact: true }).click();
  await page.getByRole('button', { name: '生成邀请链接' }).click();
  await page.getByRole('dialog', { name: '邀请链接已生成' }).waitFor();
  const activation = await page.getByRole('textbox', { name: '邀请链接', exact: true }).inputValue();
  assert.match(activation, /\/activate#token=/);
  await page.getByRole('button', { name: /^完\s*成$/ }).click();
  await page.getByRole('dialog', { name: '邀请链接已生成' }).waitFor({ state: 'hidden' });
  await screenshot(page, 'members-desktop');

  const engineer = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mobile = await engineer.newPage(); mobile.setDefaultTimeout(12000);
  await mobile.goto(activation);
  await mobile.getByRole('heading', { name: '接受内部邀请' }).waitFor();
  await mobile.getByLabel('显示姓名').fill('维修工程师 · 王师傅');
  await mobile.getByLabel('设置密码').fill('FixtureOnly3579!');
  await screenshot(mobile, 'activation-mobile');
  await mobile.getByRole('button', { name: '验证并激活账号' }).click();
  await mobile.getByText('账号已激活', { exact: true }).waitFor();
  await mobile.getByRole('button', { name: '前往登录' }).click();
  await screenshot(mobile, 'login-mobile');
  await login(mobile, 'browser-engineer@repair.example', 'FixtureOnly3579!');
  await mobile.getByRole('heading', { name: '欢迎，维修工程师 · 王师傅' }).waitFor();
  assert.equal(await mobile.getByRole('menuitem', { name: '成员与权限' }).count(), 0);
  await screenshot(mobile, 'engineer-mobile');
  const forbidden = await mobile.evaluate(async () => {
    const response = await fetch('/api/v1/auth/token/refresh', {method:'POST',credentials:'include',headers:{'X-Repair-CSRF':'1'}});
    const token = (await response.json()).accessToken;
    return (await fetch('/api/v1/repair/members',{headers:{Authorization:'Bearer '+token}})).status;
  });
  assert.equal(forbidden, 403);
  results.push('HQ invitation → mobile activation → engineer login; server rejects HQ API');

  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot(page, 'members-mobile');
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ checks: results, pageErrors: errors, backend: 'real isolated MySQL and Redis, no mocks' }, null, 2));
  process.stdout.write(JSON.stringify({ passed: results.length, screenshots: out }) + '\n');
})().catch(async error => {
  process.stderr.write(error.stack + '\n');
  if (browser) {
    for (const [index, context] of browser.contexts().entries()) for (const [n, page] of context.pages().entries()) {
      await page.screenshot({ path: path.join(out, `failure-${index}-${n}.png`), fullPage: true }).catch(() => {});
      fs.writeFileSync(path.join(out, `failure-${index}-${n}.txt`), await page.locator('body').innerText().catch(() => ''));
    }
  }
  process.exitCode = 1;
}).finally(async () => { if (browser) await browser.close(); server.close(); });
