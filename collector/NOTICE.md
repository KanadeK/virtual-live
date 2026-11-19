# 抖音 Lightweight 采集组件

采集、Protobuf 解码和标准化代码来自 [ymstar/dyhub](https://github.com/ymstar/dyhub)，固定提交 `77ab1fbb3ac6cb0886af40cf32865d998d9e6aab`（2026-10-28 获取）。MIT 许可原文见 `LICENSE.dyhub`。

本项目新增 `src/worker.ts`，通过 stdin/stdout 与 Java 后端连接；对轻量会话增加停止检查、失败后连接清理及 HTTP 状态检查、gzip 标识兼容、可选代理。去掉上游 Cookie 文件存储模块，仅使用本次连接传入的 Cookie。不会运行浏览器采集内核。全部采集结果显示在管理台；新评论接入后端 DeepSeek 回复与豆包 TTS 队列，新进场与礼物净增量使用语料直接TTS；45秒前历史事件不播报。

`third_party/douyin-sign/a_bogus.js` 和 `sign.js` 为 dyhub 随附的独立 AGPL-3.0 第三方资产，保持原样，来源及上游许可链接见该目录 README。其许可不属于本项目的 MIT 声明；发布或分发时应保留相关源码和许可。

运行：`npm ci && npm run build`。由后端按需启动 `node dist/worker.js`，Cookie 仅通过标准输入传入进程内存；没有 Cookie 持久化操作。礼物推送通常需要有效登录 Cookie；平台改动可能导致签名失效。

`src/login.ts` 是可选的临时 Chrome 登录助手：用户点击管理台按钮后打开独立窗口，自行扫码，成功后自动把 Cookie 交给本机后端并关闭临时浏览器。它不读取日常浏览器资料，也不参与后续采集。

## B站协议依据（2026-10-29）

`src/bilibili/` 是本项目新增适配，复用已有ws/protobufjs及Node内置压缩库，没有引入或执行第三方B站SDK源码。最小Protobuf字段声明基于公开线格式，非复制整套SDK。

- [B站官方开放平台文档](https://open-live.bilibili.com/document/eba8e2e1-847d-e908-2e5c-7a1ec7d9266f)：官方项目/身份码方式由服务器下发websocket_info；本轮仅核对授权边界，没有实现开放平台应用签名或宣称获得该授权。
- [blivedm网页端实现](https://github.com/xfgryujk/blivedm/blob/3bf17fe862b8c2fc6bc04c81c3f0c5db95de93d6/blivedm/clients/web.py)、[包头/压缩/认证](https://github.com/xfgryujk/blivedm/blob/3bf17fe862b8c2fc6bc04c81c3f0c5db95de93d6/blivedm/clients/ws_base.py)、[当前消息分发](https://github.com/xfgryujk/blivedm/blob/3bf17fe862b8c2fc6bc04c81c3f0c5db95de93d6/blivedm/handlers.py)、[V2最小字段号](https://github.com/xfgryujk/blivedm/blob/3bf17fe862b8c2fc6bc04c81c3f0c5db95de93d6/blivedm/models/pb.py)：读取dev实际提交3bf17fe8，验证nav/WBI、getDanmuInfo服务器token、op7/8、压缩和2026灰度消息格式。本实现没有沿用该项目的无token/默认服务器降级。
- [WBI固定测试向量及编码](https://github.com/pskdje/bilibili-API-collect/blob/main/docs/misc/sign/wbi.md)：仅核对签名线格式及独立测试向量。动态鉴权参数始终正常请求取得，不写死令牌。

上述维护者资料是网页端协议依据，不能替代B站官方对任意房间、游客或账号的访问许可。平台拒绝时停止并提示正常登录/授权。真实房间、游客/扫码可用性本轮未验证；功能范围及夹具见 [README.md](README.md)。原抖音MIT/AGPL资产及许可保持原样。
