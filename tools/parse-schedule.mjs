/**
 * 课表 HTML → 结构化 JSON 解析器
 * 同时作为 Android 端 Kotlin 解析逻辑的「黄金参照实现」与测试夹具生成器
 *
 * 源：/jsxsd/xskb/xskb_list.do?viweType=0  （一个展示整学期所有周的 <table class="qz-weeklyTable">）
 *
 * 表格结构：
 *   thead>tr>th  第一列=周次，其余 7 列=星期一..星期日（当天有 qz-currentWeek 类）
 *   tbody>tr     每行 = 一个节次（第一列 td[name=timeTd] 内含 第X节 + 时间）
 *     td          每行 7 个课程格；空格子用 qz-default
 *     有课格子    class 含 qz-hasCourse / hasMoreCourse，内部有课程 div，title 属性带完整信息
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const SRC = process.argv[2] || 'tools/out/kb-xskb_list.html';
const html = readFileSync(SRC, 'utf8');

/** 去标签取纯文本 */
const text = (s) => s
  .replace(/<[^>]*>/g, '\n')
  .replace(/&nbsp;/g, ' ')
  .replace(/&amp;/g, '&')
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/[ \t]+/g, ' ')
  .split('\n').map(x => x.trim()).filter(Boolean).join(' ');

/** 从 class 属性判断 */
const has = (cls, name) => new RegExp(`(^|\\s)${name}(\\s|$)`).test(cls || '');

// ---------- 1. 表头：星期 ----------
const thead = html.match(/<thead[\s\S]*?<\/thead>/)?.[0] || '';
const ths = [...thead.matchAll(/<th([^>]*)>([\s\S]*?)<\/th>/g)].map(m => ({
  cls: m[1].match(/class="([^"]*)"/)?.[1] || '',
  text: text(m[2]),
}));
const weekdayHeaders = ths.slice(1).map(t => t.text);
const currentWeekdayCol = ths.findIndex(t => has(t.cls, 'qz-currentWeek'));  // 0-based in ths
console.log('星期表头:', weekdayHeaders.join(' | '));
console.log('“今天”所在列(ths 下标):', currentWeekdayCol, '=> 星期', currentWeekdayCol >= 1 ? currentWeekdayCol : '?');

// ---------- 2. 当前学期 / 全部学期 ----------
const termSel = html.match(/<select[^>]*name="xnxq01id"[\s\S]*?<\/select>/)?.[0]
  || html.match(/<select[\s\S]{0,5000}?<\/select>/)?.[0] || '';
const terms = [...termSel.matchAll(/<option([^>]*)value="([^"]*)"[^>]*>([^<]*)</g)]
  .map(m => ({ id: m[2], name: m[3].trim(), selected: /selected/i.test(m[1]) }));
const currentTerm = terms.find(t => t.selected)?.id || terms[0]?.id;
console.log('当前学期:', currentTerm, ' 可选学期数:', terms.length);

// ---------- 3. 节次（时间）----------
const tbody = html.match(/<tbody[\s\S]*<\/tbody>/)?.[0] || html;
const rows = [...tbody.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/g)].map(m => m[1]);
console.log('tbody 行数:', rows.length);

const periods = [];
const grid = [];   // grid[rowIdx][dayIdx] = 单元格原文
rows.forEach((row, ri) => {
  const tds = [...row.matchAll(/<td([^>]*)>([\s\S]*?)<\/td>/g)];
  if (!tds.length) return;
  // 第一列 = 节次
  const first = tds[0];
  const firstName = first[1].match(/name="timeTd"/) ? 'timeTd' : '';
  const ftxt = text(first[2]);
  // 形如 "第一二节 08:30~09:55"
  const m = ftxt.match(/(第[一二三四五六七八九十]+[二三四五六]?节)\s*([\d:~\-\s]+)?/);
  const period = {
    index: periods.length,
    rowIndex: ri,
    label: m?.[1] || ftxt.slice(0, 20),
    time: (m?.[2] || '').trim(),
    isTimeTd: !!firstName,
  };
  periods.push(period);

  const cells = tds.slice(1).map(td => ({
    cls: td[1].match(/class="([^"]*)"/)?.[1] || '',
    raw: td[2],
    txt: text(td[2]),
    title: td[1].match(/title="([^"]*)"/)?.[1] || td[2].match(/title="([^"]*)"/)?.[1] || '',
    rowspan: parseInt(td[1].match(/rowspan="(\d+)"/)?.[1] || '1', 10),
  }));
  grid.push(cells);
});

console.log('\n节次:');
periods.forEach(p => console.log(`   [${p.index}] ${p.label} ${p.time}  (timeTd=${p.isTimeTd}, 列数=${grid[p.index].length})`));

// ---------- 4. 抽取课程 ----------
const courses = [];
grid.forEach((cells, ri) => {
  cells.forEach((c, di) => {
    if (!has(c.cls, 'qz-hasCourse') && !has(c.cls, 'hasMoreCourse')) return;
    if (c.rowspan > 1) {
      // 只处理合并单元格的第一行，避免重复
    }
    courses.push({
      periodIndex: ri,
      periodLabel: periods[ri]?.label,
      periodTime: periods[ri]?.time,
      weekday: di + 1,                       // 1=周一
      rowspan: c.rowspan,
      cls: c.cls.replace(/\s+/g, ' ').trim(),
      title: c.title,
      text: c.txt,
      htmlLen: c.raw.length,
    });
  });
});

console.log('\n有课单元格数:', courses.length);
const withTitle = courses.filter(c => c.title);
console.log('带 title 属性的:', withTitle.length);
console.log('\n===== 前 15 个课程单元格 =====');
courses.slice(0, 15).forEach((c, i) => {
  console.log(`\n--- #${i} 星期${c.weekday} ${c.periodLabel} ${c.periodTime} rowspan=${c.rowspan}`);
  console.log(`    class: ${c.cls}`);
  console.log(`    title: ${c.title.slice(0, 300)}`);
  console.log(`    text : ${c.text.slice(0, 300)}`);
});

// ---------- 5. 输出 JSON ----------
const result = {
  source: SRC,
  fetchedAt: new Date().toISOString(),
  currentTerm,
  terms,
  weekdayHeaders,
  currentWeekday: currentWeekdayCol >= 1 ? currentWeekdayCol : null,
  periods: periods.map(p => ({ label: p.label, time: p.time })),
  courses,
};
mkdirSync('tools/out', { recursive: true });
writeFileSync('tools/out/schedule-parsed.json', JSON.stringify(result, null, 2), 'utf8');
console.log('\n✅ 已输出 tools/out/schedule-parsed.json');

// ---------- 6. 打印一个课程格的原始 HTML，供 Kotlin 解析器对照 ----------
const sample = grid.flat().find(c => has(c.cls, 'qz-hasCourse') && c.raw.length > 200);
if (sample) {
  console.log('\n===== 课程格原始 HTML 样本 (前 4000 字) =====');
  console.log(sample.raw.slice(0, 4000));
}
