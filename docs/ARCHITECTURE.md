# 架构

| 模块 | 职责 |
| --- | --- |
| GameVpnService | VPN 授权、前台服务、TUN、线程和停止流程 |
| vpn_bridge.c | zdtun 转发、JNI、zstd、网络校验 |
| GameDecoder | 帧边界、分片、去重、消息及动作解码 |
| GameState | 按连接维护身份、坐标、HP 与相机上下文 |
| NearestTarget | 新鲜度与身份筛选、最近目标及有效期 |
| ViewRewrite | 上行视角向量改写与重传缓存 |
| RotationRewrite | 已有自身 Rotation 改写及缺失水平动作补齐 |
| Projection / OverlayService | 原始状态投影、ESP、拖动开关 |
| LiveRuntime | 不可变目标表与线程间状态 |

转发线程不做磁盘 I/O。分析队列超限时只丢弃分析副本，转发继续。发布版不创建运行日志；保留的少量内存计数用于兼容既有转发回调，不对用户显示。

停止 VPN 会关闭跟随与悬浮层、停止原生转发、释放 TUN 和连接资源。默认只接管 manifest 和 GameVpnService 指定的目标包。

核心协议、目标选择、投影和改写文件与 r12 基线逐文件比对，结果见 SOURCE_BASELINE.json。界面、服务诊断接线、权限和构建属于本次清理范围。
