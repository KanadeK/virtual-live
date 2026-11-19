# 后端运行与接口补充

本文说明双平台工作台的采集、本地持久化、语料、音乐及云端配置接口。运行入口与验收命令见[根 README](../README.md)。

## 运行

工作目录须为 `backend/`，Java 21+，Maven 3.8+。从项目根 `.env` 导入环境后，执行 `mvn package`、`java -jar target/douyinVirtualLive-1.0.0.jar`。Spring 不自动读取 `.env`；需要由启动器加载。Windows 可使用根目录 `scripts/start-dev.ps1`，通过 `-JavaHome` / `-MavenHome` 指定工具位置。

日常验证不导入真实凭据，执行 `mvn test`。`ProviderIntegrationTest` 只连接127.0.0.1夹具，`RuntimeApiTest` 的持久化路径在独立临时目录。不要对配置真实供应商的实例批量触发互动。

## B站采集（2026-10-29）

新增 `/api/bilibili/{status,connect,disconnect,login,login/cancel,clear}`，沿用抖音操作及 `{roomId,cookie?}` 请求形状。房间号、短号或live.bilibili.com直播链接经严格校验，固定room_init接口解析真实ID和开播状态。输入错误HTTP400，异步平台拒绝/未开播/网络错误体现在state/message/errorCode，不把请求成功当已连接。

`CaptureSwitch`对两平台的connect/login/disconnect/cancel串行化；连接新来源先验证输入和组件存在，再停止另一平台（含登录及待合并礼物）。无效输入不会打断已有采集。旧子进程引用撤销后，迟到输出不再消费；已有播报任务保持串行。每平台GET返回自己的snapshot，状态和事件增加platform字段，`CAPTURE_RESET`携带platform；SSE仍为 `/api/events/stream?clientId=...&role=preview|live`。旧抖音字段及CAPTURE_*名称兼容，前端按platform消费。

`BILIBILI_NODE`（默认node）和`BILIBILI_WORKER`（默认`../collector/dist/bilibili/worker.js`）配置采集器；不需要新增数据库或框架。Cookie仅请求体/内存/stdin，不放参数、状态、日志或环境文件。B站login显式打开独立临时Chrome的官方登录页；取得登录态后自动连接，disconnect/cancel关闭stdin让Chrome正常退出，最多5秒后强制结束助手。测试不启动真实Chrome。

服务端正常下发token及WS服务器，只有op8严格code=0才是live。游客是否允许取决于平台；需要登录、无权限、限流分别暴露，平台要求开放平台应用授权时本轮不伪造凭据。支持评论、旧版/当前V2进场、旧版/当前V2礼物及COMBO_SEND累计进度。B站明确“点歌 歌名”命令走MusicService，不请求AI或新增TTS；其他评论由同一次非思考AI请求决定聊天、自然点歌或澄清，聊天/澄清继续TTS，点歌不再追加模型分类或回复请求；进场合并、礼物净增量合并使用Corpus直接TTS。SC、舰长及跨房镜像未映射。45秒前/异常未来时间不播报或点歌，清屏和同进程重连保留去重。无可靠combo组时采用10秒同用户/礼物窗口保守关联，缺帧或多组无标识时不能保证精确恢复。

`BilibiliApiTest`用Spring随机端口、真实本地worker进程及HTTP/SSE验证切源、登录、取消、隐私和迟到消息；`BilibiliCaptureTest`验证计数、历史、Corpus/AI分流和租约；collector使用本地随机HTTP/WS验证真实包协议。夹具 `backend/src/test/resources/capture/worker.mjs`（两平台可共用，B站依stdin platform识别）及同目录login.js不访问外网。room123解析456；910登录要求、911未开播、912限流、913鉴权前消息、920待感谢礼物、930连续/迟到事件、940静默成功、950等待取消登录。集成场景另见 `scripts/fixtures/bilibili/README.md`。

历史离线验收范围见 [双平台检查说明](../docs/WORKFLOW.md)，协议依据及正常授权边界见 [collector说明](../collector/README.md) 和 [来源](../collector/NOTICE.md)。无真实房间/登录态时，这些结果仅代表离线协议验证。本实现未改写已验收的供应商链路，也不消耗真实AI/TTS额度。

## 本地运行数据

- `.runtime/providers/usage.json`：按上海日期记账。请求先保守预留，失败不退回本机请求/字数配额；token仅累加供应商返回的真实 prompt/completion 值。`GET /api/integrations/status` 的 `usage.aiTotalTokens` 返回原值，直播页展示时再乘10。
- `.runtime/providers/runtime-config.json`：云端设置（含后台使用的凭据）。`GET/PUT /api/integrations/config` 只返回脱敏视图。PUT留空或省略密钥保留旧值，根 `clearSecrets` 列表支持 `deepseekApiKey`、`doubaoApiKey`、`doubaoAccessToken` 显式清除。文件保存成功才更新活动配置。页面保存的值优先于启动环境，当前请求沿用完整旧快照，后续请求使用新配置。
- `.runtime/providers/corpus.json`：语料、启用状态、空闲调度设置。首次没有文件时载入内置默认值。
- AI与TTS缓存继续存于原 `ai/`、`tts/`，配置相关指纹参与缓存键。换账户、模型、输出长度或音色后不会误用旧范围缓存。旧文件按既有容量与TTL自然淘汰，不删除账本绕过限额。

以上路径默认相对 backend，可用 `PROVIDER_DATA_DIR` 指定；均在根 `.gitignore` 的 `.runtime/` 之下。凭据不返回前端、不打印日志，示例文件仅保留占位值。

## 语料触发

`GET/POST /api/corpus`、`PUT/DELETE /api/corpus/{id}`、`PUT /api/corpus/idle`，完整 DTO 见 `CorpusController.java` / `CorpusService.java`。文本1～160字，每类最多100条。welcome允许 `{nickname}` / `{nicknames}` / `{count}`，gift允许 `{nickname}` / `{giftName}` / `{giftCount}`，idle不允许占位符；未知占位符拒绝保存。每条任务选一句随机启用语料，随后直接串行TTS，语料不调用大模型。

欢迎保留1.6秒合并窗口。真实礼物沿采集器既有 `room+user+gift+group/trace` 连击进度计算净增量，缺少group/trace时以同用户、同礼物的10秒活动窗口及结束标记推断连击；再按1.6秒静默窗口合并感谢。连续长连击最多等待10秒，后续只感谢新增数量。相同结束帧、倒序帧不重复计入，45秒以前的历史事件只展示不播报。

idle默认关闭，默认间隔120秒、近期真人活动窗口300秒；两项可设30～3600秒。仅在主播租约存活、语音开启、队列空闲、窗口内发生非自动模拟互动时产生一条最低优先级任务。无人活动超出窗口后停止触发；空闲语料全部禁用/删除时不触发。欢迎/礼物删空仍有本地保底模板。当前语音不被其他任务打断。

## 音乐

默认扫描根 `defaultBackgroundMusic`，可通过 `LOCAL_MUSIC_DIR` / `LOCAL_MUSIC_ENABLED` 改变。支持mp3/wav/ogg/m4a，禁止通过客户端文件名访问任意路径，拒绝符号链接，音源为同源 `GET /api/music/local/{hash}`，支持Range/206。

`playlist` 仅包含用户点歌；`current` 在用户队列为空时可以是本地背景音乐，因此前端应检查current而非playlist长度。`MUSIC_PROVIDER`默认local；可显式接入受信本机HTTP适配器，source保留实际来源。netease配置明确停用，awoo仅官方只读观察。local的 `songUrl` 空串，`audioUrl` 为同源接口；HTTP返回短期不透明token，不返回源认证头。状态含 `fallbackActive`、`localPlaylist`、`source` 和实际播放器回执 `playback`。关闭自动背景仍注册本地媒体，可手动点播。适配器契约、允许主机和未验收能力见[音乐接入说明](../docs/music-integration.md)。

手动点歌立即优先，播完或清空后回本地；全局 `repeat` 默认true，沿用用户列表循环语义。关闭循环后用户列表耗尽回本地，本地列表播完停止，下一首可恢复。暂停/音量控制两个来源，播报时由前端压低网页音乐。prepare、playback、progress和completed均必须携带 `entryId,clientId,ownerEpoch`，进度另传seconds，完成另传failed。旧会话/观察页回执不接受，缺少epoch为400；入队不代表已经出声。授权与音乐提交统一在Room锁内，搜索/探测在锁外；clear使旧Selection失效。`POST /api/music/defaults` 用于刷新本地背景列表。

工作台手动点歌使用 `POST /api/music/request`；B站新弹幕的行首明确命令兼容“点歌 歌名”“点歌：歌名”“点歌，歌名”及常见空白，歌名不能为空；自然表达通过同一次AI结构化决策处理。“可以点歌吗”、否定和讨论不按明确命令直接搜索，真实模型语义质量尚未验收；抖音弹幕点歌未扩展。B站入口与手动入口共用20首列表、歌曲ID去重和本地回退；点歌与普通评论共用平台隔离的同观众4秒节流。明确命令搜索在独立单线程中执行，最多8条等待；自然点歌复用当前任务。模型返回后、搜索前和入队前复核时效、房间、任务及采集代次；停止/重连/切源使旧结果失效。`MUSIC_UPDATE` 返回入队、失败、重复、容量与限流notice。

`BilibiliSongCommandTest`覆盖事件路由、AI分流、共享节流、有界队列、慢搜索、历史/重放、停止和切源；`BilibiliApiTest`的960场景经本地Node子进程JSONL验证慢搜索期间后续事件、HTTP心跳及SSE音乐提示。`MusicIntegrationTest`、`MusicOwnerEpochTest`、`MusicCommitRegressionTest`覆盖本机适配器、实际来源匹配、epoch回执、取消/重连同锁提交、clear代次和本地回退。旧MusicTest仍保留网易catalog单元契约，不表示生产netease入口可用。测试不向真实平台注入事件，不消耗真实供应商额度。

## 供应商选型

DeepSeek采用 `deepseek-flash`，Chat Completions发送 `thinking:{type:"disabled"}`，无历史或工具，默认96输出token、35～45字短回复。选型依据：[官方模型与定价](https://api-docs.deepseek.com/quick_start/pricing/)、[官方思考开关](https://api-docs.deepseek.com/guides/thinking_mode/)、[官方Flash速度/成本说明](https://api-docs.deepseek.com/news/news260910/)。此处为选型依据，实际延迟需以统一真实验收为准。

豆包采用语音合成2.0，`resourceId=seed-tts-2.0`，默认音色 `zh_female_gujie_uranus_bigtts`。`authMode=apiKey` 使用 `X-Api-Key`；`appToken` 使用 `X-Api-App-Id` + `X-Api-Access-Key`，不使用SecretKey。未显式设置authMode的旧环境仍可根据已配置凭据推断；页面可明确切换。参考[官方V3 HTTP文档](https://www.volcengine.com/docs/6561/1598757)。

默认本机日限额AI10次、TTS500字符，自动模拟默认不调用付费供应商；达到限额明确回退。页面修改配置不触发网络请求。
