# 开发约定

本项目是可独立运行的虚拟主播演示。默认使用 Mock AI、浏览器语音和本地音乐；不要在测试时读取个人配置或调用付费服务。

- 修改前阅读 README 和相关模块；保留工作树已有修改，不使用 reset --hard 或 clean。
- 凭据、Cookie、个人角色、直播截图、运行账本、浏览器资料及构建产物不能提交。
- 保持单主播租约、串行播报、事件去重、真实播放回执与平台错误边界；不能伪造 ready、ACK 或礼物收入。
- 源码修改应同步相应测试和文档。前端 `npm run build`；采集器 `npm run build` 后 `npm test`；后端 `mvn test`。
- 界面修改使用独立 localhost 实例及 Mock 配置验收，不控制用户已有浏览器或运行服务。
- 不自动开播或推流。第三方来源与许可证见 NOTICE.md 和 collector/NOTICE.md，必须保留。
