/**
 * 阶段一验收：在真浏览器里走一遍业务，然后客观断言三层图是否正确。
 *
 * 用法： node accept.mjs
 * 依赖： 宿主机已装 Chrome（自动探测路径），或设 CHROME_PATH 环境变量
 *
 * ★ 头号断言：API 节点数 == 实际调用的接口数。
 *   如果前后端 API 事件去重做错了，这个数会翻倍 —— 那是阶段一最担心的翻车点。
 *
 * 设计原则：不靠"看起来对"，全部是机器可判定的断言 + 截图存证。
 */
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import path from 'node:path';

const OUT = path.resolve('shots');
fs.mkdirSync(OUT, { recursive: true });

const WEB = process.env.WEB_URL || 'http://localhost:5173';
const API = process.env.API_URL || 'http://localhost:8080';

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);

const chromePath = CHROME_CANDIDATES.find((p) => fs.existsSync(p));
if (!chromePath) {
  console.error('找不到 Chrome。请设置 CHROME_PATH 环境变量。');
  process.exit(2);
}

const results = [];
function check(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log(`  ${pass ? '✅' : '❌'} ${name}${detail ? '  — ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

console.log('启动浏览器:', chromePath);
const browser = await puppeteer.launch({
  executablePath: chromePath,
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1280,900'],
});

const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 900 });

const consoleErrors = [];
const jsErrors = [];
const httpErrors = [];
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  const t = m.text();
  // 资源加载 404 不算 JS 报错，单独归类（下面的 httpErrors 会列出真实 URL）
  if (/Failed to load resource/.test(t)) return;
  consoleErrors.push(t);
});
page.on('pageerror', (e) => jsErrors.push('pageerror: ' + e.message));
page.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.url()}`);
});

try {
  // ── 1. 订单列表 ────────────────────────────────────────────────
  console.log('\n[1/5] 打开订单列表');
  await page.goto(`${WEB}/order/list`, { waitUntil: 'networkidle2' });
  await page.waitForSelector('table tbody tr', { timeout: 20000 });
  const rowCount = await page.$$eval('table tbody tr', (rs) => rs.length);
  check('订单列表渲染出数据行', rowCount > 0, `${rowCount} 行`);
  await page.screenshot({ path: path.join(OUT, '01-order-list.png') });

  // ── 2. 进入详情 ────────────────────────────────────────────────
  console.log('\n[2/5] 点击「详情」');
  await page.click('button.btn-detail');
  await page.waitForSelector('#btn-save', { timeout: 20000 });
  await page.waitForFunction(() => document.querySelector('#input-customer')?.value, { timeout: 20000 });
  await page.screenshot({ path: path.join(OUT, '02-order-detail.png') });

  // ── 3. 修改并保存 ──────────────────────────────────────────────
  console.log('\n[3/5] 修改客户名并保存');
  await page.click('#input-customer', { clickCount: 3 });
  await page.type('#input-customer', '验收-张三');
  await page.select('#select-status', 'SHIPPED');
  await page.click('#btn-save');
  await page.waitForFunction(
    () => /已保存/.test(document.body.innerText),
    { timeout: 20000 }
  ).catch(() => {});
  await sleep(2000); // 等前端批量上报
  await page.screenshot({ path: path.join(OUT, '03-saved.png') });

  // ★ 同页两栏：左业务 / 右实时观测（本轮新增的核心交付）
  console.log('\n[3.5/5] 同页两栏视图');
  await sleep(1500);
  const streamRows = await page.$$eval('.stream-list .ev', (rs) => rs.length).catch(() => 0);
  const hasPanel = (await page.$('.live[data-observe-ignore]')) !== null;
  check('同页存在观测面板', hasPanel);
  check('左栏实时事件流有记录', streamRows > 0, `${streamRows} 条`);
  const sid = await page.evaluate(() => sessionStorage.getItem('observe.sessionId'));
  const evCount = async () => {
    const g = await (await fetch(`${API}/observe/graph?sessionId=${sid}`)).json();
    return g.eventCount;
  };
  const before = await evCount();
  // 连点 3 下面板自身的按钮（若 data-observe-ignore 失效，每次都会新增一条 CLICK 事件）
  await page.click('#btn-reload');
  await sleep(400);
  await page.click('#btn-fit');
  await sleep(400);
  await page.click('#btn-current');
  await sleep(2500); // 等前端 flush(1.2s) + 后端落库
  const after = await evCount();
  check('★ 面板自身按钮不污染图（data-observe-ignore 生效）', before === after,
    `连点 3 次面板按钮前后：事件 ${before} → ${after}${before === after ? '（未新增）' : '（新增了 ' + (after - before) + ' 条）'}`);
  await page.screenshot({ path: path.join(OUT, '03b-console.png'), fullPage: false });
  console.log('  截图: shots/03b-console.png（同页两栏）');

  // ── 4. 打开观察图 ──────────────────────────────────────────────
  console.log('\n[4/5] 打开「操作路线图」');
  const sessionId = await page.evaluate(() => sessionStorage.getItem('observe.sessionId'));
  check('前端已生成会话 ID', !!sessionId, sessionId);
  await page.click('#btn-go-graph');
  await page.waitForSelector('.canvas canvas', { timeout: 30000 });
  await sleep(1200);
  // 显式切到当前会话，避免竞态
  await page.click('#btn-current');
  await sleep(2500);
  await page.screenshot({ path: path.join(OUT, '04-graph.png'), fullPage: true });
  // 另存一张「不缩放的首屏」，用来判断文字实际可读性
  await page.screenshot({ path: path.join(OUT, '05-graph-top.png') });
  console.log('  截图: shots/04-graph.png（全图）, shots/05-graph-top.png（首屏）');

  // 图渲染的客观事实（不靠“看起来对”）
  const gi = await page.evaluate(() => window.__graphInfo || null);
  console.log('  图渲染信息:', JSON.stringify(gi));
  check('G6 布局未抛错', gi && gi.layout === 'ok', JSON.stringify(gi));
  check('画布按内容撑高（长链不被压缩）', gi && gi.canvasH >= 620, `canvasH=${gi && gi.canvasH}`);

  // ── 5. 客观断言 ────────────────────────────────────────────────
  console.log('\n[5/5] 图数据客观断言');
  const g = await (await fetch(`${API}/observe/graph?sessionId=${sessionId}`)).json();
  const stats = await (await fetch(`${API}/observe/stats`)).json();

  check('图里有节点', g.nodeCount > 0, `节点=${g.nodeCount} 边=${g.edgeCount} 事件=${g.eventCount}`);

  const byLayer = (l) => g.nodes.filter((n) => n.layer === l);
  check('三层节点齐全', byLayer('FRONTEND').length > 0 && byLayer('BACKEND').length > 0 && byLayer('RESOURCE').length > 0,
    `前端=${byLayer('FRONTEND').length} 后端=${byLayer('BACKEND').length} 资源层=${byLayer('RESOURCE').length}`);

  const pageNodes = g.nodes.filter((n) => n.type === 'PAGE_VIEW');
  const actionNodes = g.nodes.filter((n) => n.type === 'CLICK');
  const apiNodes = g.nodes.filter((n) => n.type === 'API');
  const sqlNodes = g.nodes.filter((n) => n.type === 'SQL');
  const redisNodes = g.nodes.filter((n) => n.type === 'REDIS');
  check('有 PAGE 节点', pageNodes.length > 0, `${pageNodes.length} 个`);
  check('有 ACTION 节点', actionNodes.length > 0, `${actionNodes.length} 个`);
  check('有 API 节点', apiNodes.length > 0, `${apiNodes.length} 个`);
  check('有 SQL 节点', sqlNodes.length > 0, `${sqlNodes.length} 个`);
  check('有 REDIS 节点', redisNodes.length > 0, `${redisNodes.length} 个`);

  // ★★ 头号断言：前后端 API 事件必须塌成同一个节点
  const apiPaths = [...new Set(apiNodes.map((n) => n.id))];
  const expectedApis = ['api:/api/order/list', 'api:/api/order/{id}', 'api:/api/order/{id}/save'];
  const uniq = expectedApis.filter((e) => apiPaths.includes(e));
  check(
    '★ API 节点无重复（去重规则生效）',
    uniq.length === expectedApis.length && apiNodes.length === expectedApis.length,
    `期望 ${expectedApis.length} 个接口节点，实际 ${apiNodes.length} 个: ${apiPaths.join(', ')}`
  );

  const edgeTypes = [...new Set(g.edges.map((e) => e.type))];
  check('TRIGGER 边存在（人的操作 → 接口/页面）', edgeTypes.includes('TRIGGER'), edgeTypes.join(','));
  check('★ AUTO 边存在（页面自动发的请求，与人触发的严格区分）', edgeTypes.includes('AUTO'), edgeTypes.join(','));
  check('CALL 边存在（接口 → 资源层）', edgeTypes.includes('CALL'), edgeTypes.join(','));

  // ★★ 边界语义正确性：TRIGGER 必须从 action 出发，AUTO 必须从 page 出发
  const humanApi = g.edges.filter((e) => e.type === 'TRIGGER' && e.target.startsWith('api:'));
  const autoApi = g.edges.filter((e) => e.type === 'AUTO' && e.target.startsWith('api:'));
  check('★ 人为触发的接口边起点是 action:', humanApi.length > 0 && humanApi.every((e) => e.source.startsWith('action:')),
    `${humanApi.length} 条，起点: ${[...new Set(humanApi.map((e) => e.source.split(':')[0]))].join(',')}`);
  check('★ 自动触发的接口边起点是 page:', autoApi.length > 0 && autoApi.every((e) => e.source.startsWith('page:')),
    `${autoApi.length} 条，起点: ${[...new Set(autoApi.map((e) => e.source.split(':')[0]))].join(',')}`);

  // ★★ 最强的一条：点「保存」触发的接口，必须归属到 action，而不是页面自动
  const saveEdge = g.edges.find((e) => e.target === 'api:/api/order/{id}/save');
  check('★ 「保存」接口归属人为点击（非页面自动）',
    !!saveEdge && saveEdge.type === 'TRIGGER' && saveEdge.source.startsWith('action:'),
    saveEdge ? `${saveEdge.source} --${saveEdge.type}--> ${saveEdge.target}` : '未找到该接口的入边');

  // 页面挂载拉列表 → 应是 AUTO（页面自动），不是人点的
  const listEdge = g.edges.find((e) => e.target === 'api:/api/order/list');
  check('★ 列表接口归属页面自动加载（非人为）',
    !!listEdge && listEdge.type === 'AUTO' && listEdge.source.startsWith('page:'),
    listEdge ? `${listEdge.source} --${listEdge.type}--> ${listEdge.target}` : '未找到该接口的入边');

  // 资源层事件必须挂在 API 节点下（parent 是 api:）
  const callEdges = g.edges.filter((e) => e.type === 'CALL');
  check('CALL 边的起点都是 API 节点', callEdges.length > 0 && callEdges.every((e) => e.source.startsWith('api:')),
    `${callEdges.length} 条`);

  check('采集队列无丢弃', stats.dropped === 0, `received=${stats.received} written=${stats.written} dropped=${stats.dropped}`);
  check('采集落库无失败', stats.writeErrors === 0, `writeErrors=${stats.writeErrors}`);
  check('无 JS 异常（pageerror）', jsErrors.length === 0, jsErrors.slice(0, 3).join(' | ') || '无');
  check('无 console 错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | ') || '无');
  check('无 HTTP 4xx/5xx', httpErrors.length === 0, httpErrors.slice(0, 3).join(' | ') || '无');

  // ★「只看人为」过滤：勾上后节点/边应变少，且不应出现 AUTO/PRECEDES
  console.log('\n[5b/5] 「只看人为」过滤');
  await page.click('#btn-current');
  await sleep(2000);
  const allInfo = await page.evaluate(() => window.__graphInfo);
  await page.click('#chk-human');
  await sleep(1200);
  const humanInfo = await page.evaluate(() => window.__graphInfo);
  console.log('  只看人为时:', JSON.stringify(humanInfo));
  check('★ 只看人为：节点/边被正确过滤',
    humanInfo && humanInfo.nodes > 0 && humanInfo.nodes <= allInfo.nodes && humanInfo.edges < allInfo.edges,
    `全部 ${allInfo && allInfo.nodes}节点/${allInfo && allInfo.edges}边 → 只看人为 ${humanInfo && humanInfo.nodes}节点/${humanInfo && humanInfo.edges}边`);
  await page.screenshot({ path: path.join(OUT, '06-human-only.png') });
  await page.click('#chk-human');
  await sleep(600);
} catch (e) {
  check('执行过程未抛异常', false, e.message);
  await page.screenshot({ path: path.join(OUT, '99-failure.png') }).catch(() => {});
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.pass);
console.log('\n════════════════════════════════════════');
console.log(`  通过 ${results.length - failed.length}/${results.length}`);
console.log('════════════════════════════════════════');
if (failed.length) {
  console.log('失败项：');
  for (const f of failed) console.log(`  ❌ ${f.name} — ${f.detail || ''}`);
  process.exit(1);
}
console.log('🎉 阶段一验收全部通过');
