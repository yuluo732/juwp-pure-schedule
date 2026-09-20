/**
 * 拉取课表页 /jsxsd/xskb/xskb_list.do 并保存原始 HTML 作为解析夹具
 */
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { ACCOUNT, PASSWORD } from './_credentials.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const CAS = 'https://eapp2.juwp.edu.cn:9443';
const JW80 = 'http://jiaowu.juwp.edu.cn';
const JW8080 = 'http://jiaowu.juwp.edu.cn:8080';
const SERVICE = `${JW80}/sso.jsp`;
// 凭据从环境变量 / tools/.env 读取，绝不硬编码（见 tools/_credentials.mjs）

const jar = new Map();
const ck = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
function absorb(r) { for (const c of (r.headers.getSetCookie ? r.headers.getSetCookie() : [])) { const [p] = c.split(';'); const i = p.indexOf('='); if (i > 0) jar.set(p.slice(0, i).trim(), p.slice(i + 1).trim()); } }
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
async function req(url, o = {}) {
  const h = { 'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8', 'Accept-Language': 'zh-CN,zh;q=0.9', ...(o.headers || {}) };
  if (jar.size) h['Cookie'] = ck();
  const r = await fetch(url, { method: o.method || 'GET', headers: h, body: o.body, redirect: o.redirect || 'manual' });
  absorb(r); return r;
}

mkdirSync('tools/out', { recursive: true });

// ---- 登录（复用已验证的 SSO 链）
// 第 1 跳：访问 SSO 入口，服务端会下发 bzb_njw 这个 Cookie，后续 CAS 需要它
await req('https://jiaowu.juwp.edu.cn:81/sso.jsp', { redirect: 'manual' });
const lu = `${CAS}/cas/login?service=${encodeURIComponent(SERVICE)}`;
const ex = (await (await req(lu)).text()).match(/name="execution"\s+value="([^"]+)"/)[1];
const pr = await req(lu, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Origin': CAS, 'Referer': lu }, body: new URLSearchParams({ username: ACCOUNT, password: PASSWORD, execution: ex, _eventId: 'submit' }).toString() });
let cur = pr.headers.get('location');
for (let i = 0; i < 8 && cur; i++) {
  const r = await req(cur, { redirect: 'manual' });
  const loc = r.headers.get('location');
  console.log(`登录链 [${i}] ${cur.slice(0, 90)} -> ${r.status}${loc ? ' -> ' + loc.slice(0, 70) : ''}`);
  cur = loc ? (loc.startsWith('http') ? loc : new URL(loc, cur).href) : null;
}
console.log('cookie:', [...jar.keys()].join(', '));

// ---- 拉课表
async function dump(path, { method = 'GET', body, tag } = {}) {
  const url = JW8080 + path;
  const r = await req(url, {
    method, body,
    headers: method === 'POST' ? { 'Content-Type': 'application/x-www-form-urlencoded', 'Referer': JW8080 + '/jsxsd/framework/xsMainV.htmlx' } : { 'Referer': JW8080 + '/jsxsd/framework/xsMainV.htmlx' },
  });
  const t = await r.text();
  const isLogin = /name="userAccount"/.test(t);
  console.log(`\n[${tag || path}] ${method} -> ${r.status} len=${t.length} 需登录=${isLogin} title=${t.match(/<title>([^<]*)<\/title>/)?.[1] || '-'}`);
  if (t.length > 200) {
    const f = `tools/out/kb-${(tag || path).replace(/[^a-zA-Z0-9._-]/g, '_')}.html`;
    writeFileSync(f, t, 'utf8');
    console.log('   已保存 ' + f);
  }
  return t;
}

const kb = await dump('/jsxsd/xskb/xskb_list.do', { tag: 'xskb_list' });

// 页面里的 xnxq01id（学期）下拉项
const terms = [...kb.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)].map(m => ({ v: m[1], t: m[2].trim() }));
console.log('\n学期下拉 (' + terms.length + '):');
terms.slice(0, 12).forEach(t => console.log('   ', t.v, '=', t.t));

// 课表表格结构统计
console.log('\n表格数 =', (kb.match(/<table/g) || []).length);
console.log('含 "kbcontent" =', kb.includes('kbcontent'), ' 含 "课表" =', kb.includes('课表'));
const tbl = kb.match(/<table[\s\S]{0,6000}?<\/table>/);
if (tbl) console.log('\n第一张表前 2500 字:\n' + tbl[0].slice(0, 2500));

// 其它课表候选
await dump('/jsxsd/xskb/xskb_list.do?xnxq01id=', { tag: 'xskb_list_empty_term' });
await dump('/jsxsd/syjx/toXskb.do', { tag: 'toXskb' });
