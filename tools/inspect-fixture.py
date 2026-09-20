"""列出课表样例 HTML 每一行的每个格子内容，用于核对表格结构。

为什么需要它：手工编辑 HTML 很容易多写/漏写一个 `<td>`，
而解析器是按「每行第 N 个 td = 星期 N」定位的，多一个就等于后面全错位。
"""
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]


def main(path):
    html = open(path, encoding="utf-8").read()
    # 必须剥掉注释：文件头部的说明里含有 <td name="kbDataTd"> 之类的字样
    html = re.sub(r"(?s)<!--.*?-->", "", html)
    rows = re.findall(r"(?s)<tr>(.*?)</tr>", html)
    total = 0
    ri = 0
    for body in rows:
        if "kbDataTd" not in body:
            continue
        ri += 1
        m = re.search(r'<td name="timeTd">([^<]*)</td>', body)
        label = m.group(1).split()[0] if m else "?"
        tds = re.findall(r'(?s)<td name="kbDataTd".*?</td>', body)
        flag = "" if len(tds) == 7 else "   <<< 不是 7 个!"
        print(f"--- 行{ri} ({label}) : {len(tds)} 个 td{flag}")
        for i, td in enumerate(tds):
            names = re.findall(r'qz-hasCourse-title qz-ellipse">([^<]+)<', td)
            if names:
                total += 1
            day = DAYS[i] if i < 7 else f"超出的第{i + 1}个"
            print(f"   [{i}] {day:6} -> {'/'.join(names) if names else '(空)'}")
    print(f"\n有课的格子数 = {total}")


if __name__ == "__main__":
    main(sys.argv[1])
