/**
 * 统一读取教务系统凭据（**绝不把真实账号密码写进代码**）。
 *
 * 背景：本项目早期的一次性探测脚本把学号密码硬编码在源码里，
 * 一旦仓库公开就等于泄露账号。现在全部改为从环境变量读取。
 *
 * 用法（三选一）：
 *   1) 临时：   $env:JW_ACCOUNT="学号"; $env:JW_PASSWORD="密码"; node tools/xxx.mjs
 *   2) .env 文件（推荐，已在 .gitignore 里排除）：
 *        在项目根目录建 tools/.env，内容两行：
 *          JW_ACCOUNT=你的学号
 *          JW_PASSWORD=你的密码
 *   3) PowerShell 会话级持久化：[Environment]::SetEnvironmentVariable('JW_ACCOUNT','学号','User')
 *
 * 未配置时会直接报错退出，避免「用空账号去登录、拿到登录页却以为接口变了」这种误判。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** 读取 tools/.env（存在才读，格式为 KEY=VALUE，忽略 # 注释与空行） */
function loadDotEnv() {
  try {
    const text = readFileSync(join(here, '.env'), 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const s = line.trim();
      if (!s || s.startsWith('#')) continue;
      const i = s.indexOf('=');
      if (i <= 0) continue;
      const k = s.slice(0, i).trim();
      const v = s.slice(i + 1).trim().replace(/^["']|["']$/g, '');
      if (!(k in process.env)) process.env[k] = v;
    }
  } catch {
    // 没有 .env 是正常情况，靠环境变量即可
  }
}

loadDotEnv();

export const ACCOUNT = process.env.JW_ACCOUNT ?? '';
export const PASSWORD = process.env.JW_PASSWORD ?? '';

if (!ACCOUNT || !PASSWORD) {
  console.error(
    '\n[缺少凭据] 请先设置教务系统账号密码，二选一：\n' +
      '  1) 在项目根目录建 tools/.env，写入：\n' +
      '       JW_ACCOUNT=你的学号\n' +
      '       JW_PASSWORD=你的密码\n' +
      '  2) 或设置环境变量 JW_ACCOUNT / JW_PASSWORD\n' +
      '\n（请不要把真实凭据写进源码，仓库是公开的。）\n'
  );
  process.exit(1);
}

/** 登录页表单里 password 字段是否需要前端加密（页面里 var encrypt = "true/false"） */
export const CAS_BASE = 'https://eapp2.juwp.edu.cn:9443';
export const JW_BASE_80 = 'http://jiaowu.juwp.edu.cn';
export const JW_BASE_8080 = 'http://jiaowu.juwp.edu.cn:8080';
export const SSO_SERVICE = `${JW_BASE_80}/sso.jsp`;
