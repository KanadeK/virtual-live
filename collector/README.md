# 双平台采集器

需要Node.js20.19+/22.12+，本机验证Node24；`npm ci`、`npm run build`、`npm test`。本轮没有新增npm依赖。Java只在用户连接或登录时启动对应worker，不需要单独常驻。

## B站

- `dist/bilibili/worker.js`：首条stdin为 `{"platform":"bilibili","roomId":"123","cookie":"可选单行Cookie"}`。stdout严格JSON Lines，消息为resolved/status/event；不打印原始平台响应或凭据。关闭stdin或发送SIGTERM取消HTTP、退避计时器和WS。
- `dist/bilibili/login.js`：显式点击登录后才运行。stdin接收roomId，在独立临时Chrome中打开B站官方登录页；二维码由官方页面生成，不经第三方QR服务。不读取用户日常Chrome资料，登录成功关闭临时浏览器，把credentials JSONL仅交给本机Java，后者通过新worker的stdin使用。
- 输入仅接受正整数房间号或 `https://live.bilibili.com/123`（兼容http），短号由固定room_init接口解析到真实room_id。链接不会变成HTTP目标；API不接受自定义host，WS仅使用正常接口返回的 `*.chat.bilibili.com` wss地址及token。请求不跟随重定向。
- 接入步骤：room_init确认开播状态 → nav获取当前用户和动态WBI参数 → 必要时由B站首页下发buvid3 → 带WBI请求getDanmuInfo → 用该次token/room/uid/buvid发送op7 → 服务端op8严格code=0才显示live。没有token、鉴权拒绝或平台权限要求时停止并显示正常登录/授权提示，不降级匿名假连接。
- 连接发送30秒心跳，75秒无心跳响应重连；鉴权12秒超时。网络故障最多5次退避（3/6/12/24/30秒），每次重新解析并获取凭证。权限错误不通过自动重试绕开。游客仅在平台实际允许时可用；本轮没有真实房间或登录态验证，不能保证游客可用。

支持DANMU_MSG（含冒号变体）、INTERACT_WORD及protobuf INTERACT_WORD_V2（只欢迎msg_type=1）、SEND_GIFT、protobuf SEND_GIFT_V2批次和COMBO_SEND累计进度。SC、舰长、大航海与跨房镜像消息不映射，不重复推测感谢。

JSON数值型64位UID、礼物/组/交易ID在解析前保留十进制文本；Protobuf longs转字符串。去重ID使用平台字段或其稳定摘要，不随机补造历史时间。缺少时间的评论/进场只记录。COMBO_SEND无时间时只继承已知组时间；未知组不能制造新播报。Java按唯一交易增量总和与累计上界的最大值计算净增量，再合并同房间/用户/礼物1.6秒感谢，最长等待10秒。

SEND_GIFT_V2缺少combo ID时，以同用户同礼物10秒窗口关联累计组。缺少可靠分组、丢失交易帧或两组无标识连发时存在信息限制，采用保守计数以避免重复感谢；不声称所有缺字段场景可精确恢复。去重/连击是有界内存状态，清屏和同进程重连保留，后端重启后只按历史时间过滤。

解包支持16字节大端头、多包/分片、扩展头、zlib/gzip、Brotli嵌套；单包最多4MiB，累计解压最多8MiB、嵌套4层、每次最多2048包，错误数据明确停止。调试仅可使用stderr且不得包含Cookie/token。生产入口不开放测试HTTP/WS地址；测试通过依赖注入连接本地随机端口。

## 离线验证

`tests/bilibili.test.mjs`验证真实本地HTTP/WS、鉴权结果、心跳、重连、取消、压缩与异常边界和消息归一化；`tests/lightweight.test.mjs`保留原抖音回归。先构建再测试，测试导入dist。Java侧夹具位于 `backend/src/test/resources/capture/`，集成侧场景见 `scripts/fixtures/bilibili/README.md`。这些夹具没有真实平台或付费供应商调用。

协议来源与既有第三方许可见 [NOTICE.md](NOTICE.md)。

## 抖音

`dist/worker.js` 接收首条 stdin JSON（roomId、可选 cookie），通过 stdout JSON Lines 输出 resolved/status/event。`dist/login.js` 仅在用户点击登录后打开独立临时 Chrome；不读取日常浏览器资料。支持数字房间号、直播链接、内部 room_id 和分享文案。64 位 ID 保留为字符串，关闭 stdin 后清理本次连接。原抖音来源和独立许可继续保留在 NOTICE.md。
