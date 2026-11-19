# Virtual Live

单房间、竖屏虚拟主播演示：Java 21 / Spring Boot、Vite / TypeScript、Node.js 采集器。支持抖音与 B 站采集、聊天回复、语音、点歌、角色嘴型和运行指标，无需数据库、Redis 或 Docker。

发行版使用中性机器人角色、空凭据和 Mock AI。没有预设主播账号、目标直播间、登录态、个人账本或音乐录音。

## 快速运行

需要 JDK 21+、Maven 3.8+、Node.js 20.19+ 或 22.12+ 及 npm。登录助手可使用本机 Google Chrome。

在项目根目录执行：

```powershell
Copy-Item .env.example .env
./scripts/start-dev.ps1 -Action Start -Background
./scripts/start-dev.ps1 -Action Status
./scripts/start-dev.ps1 -Action Stop
```

macOS / Linux：

```bash
cp .env.example .env
./scripts/start-dev.sh --background
./scripts/start-dev.sh --status
./scripts/start-dev.sh --stop
```

启动器安装缺失 npm 依赖并编译各模块。服务只绑定本机地址：

- 工作台：<http://127.0.0.1:5173/control>
- 直播页：<http://127.0.0.1:5173/live>
- 静音预览：<http://127.0.0.1:5173/live?preview=1>
- 健康检查：<http://127.0.0.1:8080/api/health>

打开直播页后点击声音按钮解除浏览器自动播放限制。只有持有租约的直播页播放声音并提交完成回调，控制台和观察页保持静音。项目本身不会自动推流或开播。

## 配置自己的直播间

在工作台选择平台并输入自己的房间号。登录需要时，通过官方页面完成扫码；Cookie 只传递给本次采集进程，不随源码提供。平台拒绝、未开播、权限不足及断线均显示实际状态。

默认无需云服务。需要 AI 和 TTS 时，在本地 `.env` 或云端配置面板填写自己的供应商凭据。默认 `PAID_AUTO_ENABLED=false`，AI 日上限 10 次、TTS 日上限 500 字。公共 API 地址和配置字段保留，所有实际 Key、Token、App ID 均为空。详见 [.env.example](.env.example) 与 [后端接口](backend/README.md)。

音乐默认从 `defaultBackgroundMusic/` 读取自己的授权音频。也支持显式配置的 HTTP 音源和 QQ 桥接协议；QQ/Awoo 外部程序、账号、修改后的安装包及连接令牌不随仓库分发。参见 [音乐接入](docs/music-integration.md)。

鱼干账本需要手动设置 `FISH_ROOM_ID`，`FISH_OPENING_BALANCE` 默认 0；未配置时显示未知，且不会创建或计入任何账户。1 元推送礼物金额对应 1 条鱼干，不代表平台到账。运行费用根据供应商返回的实际用量估算，舞台显示乘 3，原始账本和预算不变；优惠、免费额度未扣减。本次聊天人数只统计当前实际采集范围的去重用户，不冒充全天。

## 角色与页面

默认角色为源码绘制的中性机器人，无个人形象。角色入口为 `frontend/src/character/profile.ts`，动作清单位于 `frontend/public/assets/character/demo/manifest.json`。现有图集渲染器可使用 PNG 或 SVG；如需替换角色，请提供具有相应权利的素材。

直播舞台为 1080×1920，保留背景、角色、前景、UI 四层。`/live?layer=background`、`avatar`、`foreground`、`ui` 可用于分层取景；用 `preview=1` 防止独立预览取得音频所有权。

## 构建与检查

```bash
cd backend
mvn -B -ntp test package
cd ../collector
npm ci
npm run build
npm test
cd ../frontend
npm ci
npm run build
```

`scripts/verify-*.mjs` 为可选的独立 Mock/本地夹具检查。发布前运行 `python scripts/check-public-source.py` 检查已跟踪文件中的凭据模式与禁止发布文件。不要在真实云端服务上运行批量 e2e，也不要将运行记录、截图或自己的 `.env` 加入 Git。

## 目录与许可

- `backend/`：REST/SSE、租约、调度、采集桥接、用量与持久账本。
- `collector/`：平台协议、事件标准化、临时登录助手。
- `frontend/`：直播页、控制台、角色、音乐与语音播放。
- `scripts/`：本地启停与隔离验收。

项目代码使用 MIT 许可。第三方采集签名组件另有 AGPL-3.0 许可，见 [NOTICE.md](NOTICE.md)。素材与音乐的许可独立于代码。
