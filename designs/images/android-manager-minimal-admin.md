# android-manager-minimal — admin

**Preview file:** designs/previews/android-manager-minimal-admin.png
**Spec SSOT:** designs/specs/android-manager-minimal.md

## Layout

三联手机页面，单屏390×844比例；Android单一表面，不生成桌面网站。白底行式内容，20dp边距，安全区与底栏预留，48dp触控。

## Modules

登录：单密码框与登录；设置：精确闹钟、目录、改密和清空密码；日志：日期、分类、查询、原始事实和分页。

## Visual details

遵循SSOT全部tokens。蓝色只强调动作、选中和限制轨道；系统中文字体，细分隔线，不使用营销卡片/渐变/emoji。数据为演示，原生交互和异常规则以SSOT为准。

## Image prompt

Use case: ui-mockup. Create a polished landscape design board showing THREE equally sized flat front-on native Android phone UI screens side by side, each 390:844 proportions, fully visible and aligned, no hardware frames or perspective. Screens are Login, Settings, Logs for Chinese native device management app 应用管控. Match a minimalist Android system-settings design exactly: background #F6F7F9, white #FFFFFF surfaces, blue #2458C6 primary, pale blue #EAF0FC, text #20252D, secondary #626B78, dividers #DCE1E8, destructive #B3261E. Native system Chinese sans-serif, 24sp titles, 16sp body, 14sp helper; 20dp margins; moderate 12dp control corners, grouped plain list sections, no shadow, no gradients, no ornamental hero graphics. Status bar and Android gesture safe area on all screens. No marketing words or giant metrics. Native vector outline icons only.
LEFT login: generously spaced single simple form in upper-middle of screen. Small outline shield icon; heading 应用管控 and secondary heading 验证管理密码. A full width outlined password input with visible label 管理密码 and 6 masked dots, eye icon optional not required. Full width blue 登录 button. Do NOT show default password, 0000, forgot-password link, helper text below input, marketing slogan, bottom navigation or social sign-in. Leave quiet whitespace.
MIDDLE settings: title 设置. White grouped row section heading 权限, row 精确闹钟 and blue 检查授权 action. Next section 密码目录, line Documents/AndroidManager/password.json wrapping cleanly. Password change section heading 修改管理密码 with two labeled masked input fields 新密码 and 再次输入密码 and blue full width 保存新密码 button. At lower section, restrained red outline full width 清空密码 button, above or below concise red/gray explanatory text 删除密码文件并退出管理，现有管控继续运行. Screen scroll affordance fine. Bottom navigation exactly 概览 应用 时间表 设置 日志, setting icon and text blue, others gray. No factory reset or device-owner removal controls.
RIGHT logs: title 日志. Labeled outlined date field 日期 with 2026-10-20 and small calendar-shaped decoration only, avoid promising datepicker. Horizontally scrollable category chips 全部 (selected blue), 登录安全, 配置变更, 管控执行, 权限异常, the last may be partially offscreen with visual scrolling affordance. A blue 查询 button. Plain list of readable entries separated by light rules. Entry 22:40　配置变更 / 保存周时间表 / 成功. Entry 22:41　管控执行 / 应用限制已更新 / 暂停与隐藏分别记录. Entry 22:42　权限异常 / 定时执行不可用 / 请授予精确闹钟权限, red error text and small outline alert icon. Do not imply those demo logs are the actual phone state. Bottom of scroll content previous and next page controls 上一页 and 下一页; empty gap then common bottom navigation, 日志 selected. Crisp readable Simplified Chinese, faithful minimal native UI spacing. No fake search for logs, no export actions or charts. Neutral gutters between screens; tiny screen captions only.

