# 可选 QQ 桥接

此仓库包含客户端协议适配，不分发 QQ/Awoo 可执行文件或个人配置。使用者需要自行安装兼容连接器、登录自己的账号并配置本机地址和令牌文件。默认配置不会连接 QQ。

接口实现见 `backend/src/main/java/live/dafeiyu/AwooNativeTransport.java`、`NativeMusicController.java` 及 `frontend/src/audio/NativeMusicClient.ts`。运行时令牌文件必须放在忽略目录，不能加入源码。

命令与回执使用真实请求标识、实例标识和当前音频所有权。清空必须对应同一请求和停止 nonce；后台状态、缓存 ready、队列为空都不能替代实际播放或暂停回执。代码中的测试令牌仅用于 localhost 夹具。
