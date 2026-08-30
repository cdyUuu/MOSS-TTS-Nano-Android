# 签名密钥配置说明

本项目使用固定签名密钥，确保升级安装时无需卸载旧版本。

## 本地构建 Release 版本

### 方式一：使用 GitHub Secrets（推荐）

密钥已存储在 GitHub Secrets 中，GitHub Actions 构建时会自动使用。

Secrets 列表：
- `KEYSTORE_BASE64` - keystore 文件的 Base64 编码
- `KEYSTORE_PASSWORD` - keystore 密码
- `KEY_ALIAS` - 密钥别名
- `KEY_PASSWORD` - 密钥密码

### 方式二：本地配置

1. 将你的 keystore 文件放到 `app/release.keystore`
2. 修改 `app/build.gradle.kts` 中的签名配置（如果密码不同）
3. 运行 `./gradlew assembleRelease`

### 方式三：使用环境变量

```bash
export KEYSTORE_PATH=/path/to/your.keystore
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
./gradlew assembleRelease
```

## 安全提示

- `*.keystore` 文件已被 `.gitignore` 排除，不会提交到仓库
- 不要将 keystore 文件或密码提交到 git
- 定期备份 keystore 文件，丢失后无法更新应用
