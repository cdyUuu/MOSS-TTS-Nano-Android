# 签名配置说明

本项目使用固定签名密钥，确保升级安装时无需卸载旧版本。

## 本地构建 Release 版本

项目已内置签名配置，直接运行：

```bash
./gradlew assembleRelease
```

签名信息：
- Keystore 文件: `app/release.keystore`
- Alias: `release`
- 密码: `mosstts123`

> 注意：`release.keystore` 已被 `.gitignore` 排除，不会上传到仓库。
> 如需在其他设备构建 release 版本，请自行备份 keystore 文件。

## GitHub Actions 自动构建

GitHub Actions 自动构建 Debug 版本，产物在 Actions 页面下载。

如需在 GitHub Actions 中构建 Release 版本：
1. 将 keystore 文件编码为 Base64
2. 在仓库 Settings > Secrets and variables > Actions 中添加：
   - `KEYSTORE_BASE64`: keystore 文件的 Base64 编码
   - `KEYSTORE_PASSWORD`: keystore 密码
   - `KEY_ALIAS`: 密钥别名
   - `KEY_PASSWORD`: 密钥密码
3. 修改工作流文件，添加签名步骤
