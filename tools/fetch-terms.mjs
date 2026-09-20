/**
 * 抓取指定学期的课表 HTML，并做「卡片显示问题」的根因分析
 *
 * 用法: node tools/fetch-terms.mjs 2024-2025-1 2025-2026-1 2025-2026-2 2029-2030-2
 */
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { ACCOUNT, PASSWORD } from './_credentials.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const CAS = 'https://eapp2.juwp.edu.cn:9443';
const JW = 'http://jiaowu.juwp.edu.cn:8080';
const SERVICE = 'http://jiaowu.juwp.edu.cn/sso.jsp';
// 凭据从环境变量 / tools/.env 读取，绝不硬编码（见 tools/_credentials.mjs）

const jar = new Map();
const ck = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
const absorb = (r) => { for (const c of (r.headers.getSetCookie ? r.headers.getSetCookie() : [])) { const [p] = c.split(';'); const i = p.indexOf('='); if (i > 0) jar.set(p.slice(0, i).trim(), p.slice(i + 1).trim()); } };
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
async function req(url, o = {}) {
  const h = { 'User-Agent': UA, 'Accept': 'text/html,*/*;q=0.8', 'Accept-Language': 'zh-CN,zh;q=0.9', ...(o.headers || {}) };
  if (jar.size) h['Cookie'] = ck();
  const r = await fetch(url, { method: o.method || 'GET', headers: h, body: o.body, redirect: o.redirect || 'manual' });
  absorb(r); return r;
}

async function login() {
  await req('https://jiaowu.juwp.edu.cn:81/sso.jsp', { redirect: 'manual' });
  const lu = `${CAS}/cas/login?service=${encodeURIComponent(SERVICE)}`;
  const ex = (await (await req(lu)).text()).match(/name="execution"\s+value="([^"]+)"/)[1];
  const pr = await req(lu, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Origin': CAS, 'Referer': lu }, body: new URLSearchParams({ username: ACCOUNT, password: PASSWORD, execution: ex, _eventId: 'submit' }).toString() });
  let cur = pr.headers.get('location');
  for (let i = 0; i < 8 && cur; i++) {
    const r = await req(cur, { redirect: 'manual' });
    const loc = r.headers.get('location');
    cur = loc ? (loc.startsWith('http') ? loc : new URL(loc, cur).href) : null;
  }
}

/** 取某学期课表 HTML */
async function fetchTerm(termId) {
  const r = await req(`${JW}/jsxsd/xskb/xskb_list.do?viweType=0`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Referer': `${JW}/jsxsd/xskb/xskb_list.do` },
    body: new URLSearchParams({ xnxq01id: termId, viweType: '0' }).toString(),
  });
  return await r.text();
}

// ---------------------------------------------------------------- 解析（与 Kotlin 同规则）
const stripTags = (s) => s.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
function parseWeeks(raw) {
  const set = new Set();
  if (!raw) return set;
  for (const seg of raw.split(/[,，、]/)) {
    const r = seg.match(/(\d+)\s*-\s*(\d+)/);
    if (r) { for (let w = +r[1]; w <= +r[2]; w++) set.add(w); }
    else { const s = seg.match(/\d+/); if (s) set.add(+s[0]); }
  }
  return set;
}

/** 解析整张课表 → { termId, periods[], cells[] } */
function parseSchedule(html, termId) {
  const tbody = html.match(/<tbody[\s\S]*<\/tbody>/)?.[0] || html;
  const rows = [...tbody.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/g)].map(m => m[1]);
  const periods = [];
  const cells = [];
  let periodRow = -1;
  for (const row of rows) {
    const tds = [...row.matchAll(/<td([^>]*)>([\s\S]*?)<\/td>/g)];
    if (!tds.length) continue;
    const firstTxt = stripTags(tds[0][2]);
    const isTime = /name="timeTd"/.test(tds[0][1]) || /第[一二三四五六七八九十]+[二三四五六]?节/.test(firstTxt);
    if (isTime) {
      periodRow++;
      const label = firstTxt.match(/第[一二三四五六七八九十]+[二三四五六]?节/)?.[0] || firstTxt.slice(0, 10);
      const time = firstTxt.match(/\d{1,2}:\d{2}\s*[~\-]\s*\d{1,2}:\d{2}/)?.[0]?.replace(/\s/g, '') || '';
      periods.push({ label, time });
    }
    tds.slice(1).forEach((td, dayIdx) => {
      const rowSpan = parseInt(td[1].match(/rowspan="(\d+)"/)?.[1] || '1', 10);
      const lis = [...td[2].matchAll(/<li[^>]*class="[^"]*courselists-item[^"]*"[\s\S]*?<\/li>/g)];
      for (const li of lis) {
        const name = stripTags(li[0].match(/<div class="qz-hasCourse-title[^"]*">([\s\S]*?)<\/div>/)?.[1] || '');
        if (!name) continue;
        const detail = stripTags(li[0]);
        const weeksRaw = detail.match(/时间[:：]\s*([^;；\[]*)\[/)?.[1]?.trim() || '';
        const room = detail.match(/地点[:：]\s*([^;；]*)/)?.[1]?.trim() || '';
        const teachers = detail.match(/老师[:：]\s*([^;；]*)/)?.[1]?.trim() || '';
        cells.push({
          weekday: dayIdx + 1, periodRow, rowSpan,
          name, teachers, room, weeksRaw,
          weeks: [...parseWeeks(weeksRaw)].sort((a, b) => a - b),
          nameLen: [...name].length,
        });
      }
    });
  }
  return { termId, periods, cells };
}

// ---------------------------------------------------------------- 主流程
mkdirSync('tools/out/terms', { recursive: true });
await login();
console.log('登录完成\n');

const terms = process.argv.slice(2);
if (!terms.length) { console.log('用法: node tools/fetch-terms.mjs <学期id> ...'); process.exit(1); }

const results = {};
for (const t of terms) {
  const html = await fetchTerm(t);
  writeFileSync(`tools/out/terms/kb-${t}.html`, html, 'utf8');
  const s = parseSchedule(html, t);
  results[t] = s;
  console.log(`【${t}】HTML=${html.length}  节次=${s.periods.length}  课程安排=${s.cells.length}`);
}

// ---------------------------------------------------------------- 根因分析
for (const t of terms) {
  const s = results[t];
  console.log(`\n${'='.repeat(72)}\n【${t}】卡片显示问题根因分析\n${'='.repeat(72)}`);
  if (!s.cells.length) { console.log('  （本学期无课程）'); continue; }

  // 1) 一格多课分布
  const byCell = new Map();
  for (const c of s.cells) {
    const k = `${c.weekday}-${c.periodRow}`;
    if (!byCell.has(k)) byCell.set(k, []);
    byCell.get(k).push(c);
  }
  const multi = [...byCell.entries()].filter(([, v]) => v.length > 1).sort((a, b) => b[1].length - a[1].length);
  console.log(`\n1) 一格多课（同格卡片堆叠 → 每张被压扁）`);
  console.log(`   有课格子总数 = ${byCell.size}，其中多课格子 = ${multi.length}`);
  for (const [k, v] of multi.slice(0, 6)) {
    const [wd, pr] = k.split('-').map(Number);
    console.log(`   · 周${wd} 行${pr}：「${periods(results[t])[pr] || '?'}」${v.length} 门`);
    for (const c of v) console.log(`       - ${c.name}  周次=${c.weeksRaw}  教室=${c.room || '(空)'}  rowSpan=${c.rowSpan}`);
  }

  // 2) 超长课名
  const long = s.cells.filter(c => c.nameLen >= 12).sort((a, b) => b.nameLen - a.nameLen);
  console.log(`\n2) 超长课名（窄列需 6+ 行，maxLines 只有 2~3 → 必截断）`);
  console.log(`   长度 >= 12 字的课程安排 = ${long.length}`);
  [...new Set(long.map(c => `${c.name} (${c.nameLen}字)`))].slice(0, 8).forEach(x => console.log('   · ' + x));

  // 3) 空括号教室
  const emptyRoom = s.cells.filter(c => /^\(\s*\)$/.test(c.room) || c.room === '()');
  console.log(`\n3) 空括号教室 "()"（会渲染成孤立的括号两行）= ${emptyRoom.length}`);
  [...new Set(emptyRoom.map(c => c.name))].forEach(x => console.log('   · ' + x));

  // 4) rowSpan（连堂课）
  const spans = s.cells.filter(c => c.rowSpan > 1);
  console.log(`\n4) rowSpan > 1（连堂课跨行，当前网格完全忽略 rowSpan）= ${spans.length}`);
  spans.forEach(c => console.log(`   · 周${c.weekday} 行${c.periodRow} 「${c.name}」rowSpan=${c.rowSpan} 周次=${c.weeksRaw}`));

  // 5) 每格按周过滤后的课程数（渲染实际复杂度）
  let worst = 0, worstKey = '';
  for (let w = 1; w <= 30; w++) {
    for (const [k, v] of byCell) {
      const n = v.filter(c => c.weeks.includes(w)).length;
      if (n > worst) { worst = n; worstKey = `第${w}周 ${k}`; }
    }
  }
  console.log(`\n5) 按周过滤后单格最多课程数 = ${worst}（${worstKey}）`);
}

function periods(s) { return s.periods.map(p => p.label); }

writeFileSync('tools/out/terms/analysis.json', JSON.stringify(results, null, 2), 'utf8');
console.log(`\n已保存 tools/out/terms/analysis.json`);
