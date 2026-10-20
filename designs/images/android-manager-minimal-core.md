# android-manager-minimal — core

**Preview file:** designs/previews/android-manager-minimal-core.png
**Spec SSOT:** designs/specs/android-manager-minimal.md

## Layout

三联手机页面，单屏390×844比例；Android单一表面，不生成桌面网站。白底行式内容，20dp边距，安全区与底栏预留，48dp触控。

## Modules

概览：总开关、当前时段、独立执行结果、退出；应用：搜索/系统筛选/黑名单及双维回执；周表：每日开关、独立全天按钮、时分选择、只读轨道、保存。

## Visual details

遵循SSOT全部tokens。蓝色只强调动作、选中和限制轨道；系统中文字体，细分隔线，不使用营销卡片/渐变/emoji。数据为演示，原生交互和异常规则以SSOT为准。

## Image prompt

Use case: ui-mockup. Create a polished landscape design board with THREE equally sized front-on Android mobile UI screens arranged side by side: Overview, Applications, Weekly Schedule. All three screens are the same native phone viewport ratio 390:844, completely visible, aligned at top and bottom, with generous narrow neutral gutters. No device hardware frames, no perspective, no floating shadows, no marketing headlines outside screens. A small Chinese caption above each screen is okay. Native Chinese app named 应用管控. Minimal, calm Android system-settings aesthetic: background #F6F7F9, white #FFFFFF surfaces, primary #2458C6, dark text #20252D, secondary text #626B78, separators #DCE1E8, pale blue #EAF0FC, red #B3261E for actual error only. Native Chinese sans-serif typography, restrained medium weight, tabular time digits. 20dp horizontal margins, clean rows, few rounded grouped sections, no decorative cards or gradients. Touch targets at least 48dp. Each screen has proper status bar and bottom system gesture safe area. Shared bottom navigation with exactly FIVE small line icons plus readable Chinese labels 概览 应用 时间表 设置 日志, current tab blue, others gray.
LEFT overview: title 概览. 管控总开关 row with blue on switch. A single pale blue low-height section reads 当前限制时段, smaller 今日 09:00–23:00 and 下一边界 23:01. Under it a plain grouped list with labeled rows 执行结果 / 已完成本轮执行; 定时执行 / 已登记下次边界; 黑名单 / 3 个应用. Small factual helper 总开关开启后，按时间表执行限制. Then a quiet outlined 退出管理 button. Leave generous whitespace; no giant metrics, no pie charts. Selected bottom nav 概览.
MIDDLE apps: title 应用, a full-width labeled search field 搜索应用名称或包名. A single filter row 仅系统应用 with unchecked checkbox. A white list of 4 app rows with small understated colored square app glyphs, text, and switches. Row1 小红书, com.xingin.xhs, blue on switch; second line 暂停已施加　隐藏已施加. Row2 浏览器, com.example.browser, on, same real dual status. Row3 时钟, com.example.clock, off, 未加入黑名单. Row4 应用管控, 本应用受保护, disabled off. No invented stats. Selected nav 应用.
RIGHT schedule: title 时间表 and subtitle 上海时间. A compact helper 结束分钟包含至第59秒. Three full day sections and a partial fourth at the bottom to indicate scrolling. Monday 周一, on switch; a distinct full-width outlined button 周一全天（已选择） on its OWN ROW; underneath two time selector fields 开始 00:00 and 结束 23:59; a thin full blue 24-hour time rail below, end labels 00 and 24. Tuesday 周二, on; independent full-width outlined button 周二设为全天; two selectors 开始 09:00 and 结束 20:00; thin gray track with a blue interval segment from 9 to 20. Wednesday 周三, off and 本日未设置; the independent full-width outlined 周三设为全天 button is STILL VISIBLE even when off; NO time selector fields under disabled day; empty thin gray rail. Keep compact but touchable spacing. Small text 有未保存的修改 above a full-width blue 保存周时间表 button anchored above bottom nav; scrolling body must not overlap button. Selected nav 时间表. Render crisp readable Chinese and accurate native form proportions. Flat high-fidelity product screenshot board, no illustrations, no gradients, no fake analytics. All data are demo design content.

