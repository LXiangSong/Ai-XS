# 模型图片能力配置

后端生成的 DSH 配置与 `model/list.data[].inputModalities` 使用同一份能力数据。当前已安装 DSH 的模型目录明确声明 `deepseek-flash` 支持 `text` 和 `image`；`deepseek-v4-pro` 与未声明能力的自定义 DeepSeek 模型默认仅 `text`。第三方网关的实际模型映射可能不同，可单独覆盖。

`provider/read.profiles[mode].modelCapabilities` 返回按精确模型 ID 保存的能力；`provider/save` 可接收同名可选字段，例如：

```json
{
  "engine": "deepseek",
  "mode": "thirdparty",
  "modelCapabilities": {
    "custom/vision": { "inputModalities": ["text", "image"] },
    "deepseek-flash": { "inputModalities": ["text"] }
  }
}
```

示例仅列新增字段，保存仍需传递现有地址、协议、模型列表等参数。键必须属于当前配置的模型列表；能力数组只支持 `text`、`image`，非空且不重复。省略字段会保留原配置；传 `{}` 恢复内置默认。更换地址或协议且没有明确传新能力时，清除旧网关的覆盖，避免把旧能力带到新服务。删除模型会同步移除其覆盖。

`provider/discover` 在模型列表明确返回 `inputModalities`、`input_modalities` 或数组形式的 `input` 时，额外返回 `modelCapabilities`。前端如需保留发现的能力，应随 `provider/save` 一起提交。仅有模型 ID 的响应不会被当成已验证支持图片。

能力声明会写入第三方 DSH 的 `models[].input`，官方 DSH 的 `models[].inputModalities`，协作执行使用冻结的同一份供应商配置。它允许 DSH 的图片工具及附件流程通过能力检查，不能证明第三方网关实际实现了图片请求。用户明确配置为纯文本的模型继续拒绝图片。

本轮未修改前端。第三方模型能力编辑界面可直接对接以上字段，附件按钮继续读取 `model/list` 的同一能力数组。
