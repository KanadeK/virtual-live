# 音乐接入

`MUSIC_PROVIDER=local` 为默认值，从 `LOCAL_MUSIC_DIR` 读取自己的授权音频；没有文件时显示空列表。

`http` 使用显式配置的 `MUSIC_GATEWAY_URL` 本机适配器，搜索与可播地址解析分开，入队前检查音频。`qq-awoo` 使用独立安装的 QQ/Awoo 连接器，设置 `MUSIC_QQ_BRIDGE_URL`、`MUSIC_QQ_BRIDGE_TOKEN_FILE`，可选 `MUSIC_AWOO_STATE_URL`。外部连接器的安装包、账号和令牌均不包含在发行版中。

桥接协议由 `NativeMusicTransport.java`、`AwooNativeTransport.java` 与 `NativeMusicClient.ts` 定义。保留请求 ID、歌曲目标、当前文档租约和停止 nonce 校验。入队、自动播放开关和缓存 ready 不等同于实际出声；只有对应请求的新鲜实际播放回执才能更新播放状态。

本地/HTTP 模式支持暂停、下一首、循环、音量及语音时降音。QQ 原生模式的播放和清空取决于外部连接器；不能将 Mock 或旧状态作为实机通过证明。未配置音源时明确显示错误，不隐式回退到外部音源。
