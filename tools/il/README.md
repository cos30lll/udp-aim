# 离线程序集检查

需要 .NET 8 SDK 和使用者自行提供的 Mono.Cecil.dll。包内不含目标游戏程序集或第三方 DLL。

```bash
dotnet build tools/il/StaticAssemblyInspect -p:CecilPath=/absolute/path/Mono.Cecil.dll
dotnet build tools/il/StaticReferenceInspect -p:CecilPath=/absolute/path/Mono.Cecil.dll
```

构建后运行对应 bin/Debug/net8.0 下的 DLL。

```text
StaticAssemblyInspect types <assembly> <output-directory> <exact-type-name>...
StaticAssemblyInspect il <assembly> <output-directory> <method-token>...
StaticReferenceInspect <assembly> <output-directory> <target-token>...
```

分析过程：导出相关类声明，记录字段和方法 token；导出动作分派的 IL；按 token 查引用，确认数据如何流入镜头写入点。输出只反映所检查的程序集，不代表运行状态成立。不要将游戏程序集或私有导出结果提交到公开仓库。
