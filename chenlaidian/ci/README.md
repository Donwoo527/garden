# CI 打包（暂未启用）

`chenlaidian.yml` 是 GitHub Actions 的打包配置。现在用的 PAT 只有 `repo` 权限，
GitHub 不允许它创建 `.github/workflows/` 下的文件（需要 `workflow` 权限）。

启用步骤（小陈操作，一分钟）：GitHub → Settings → Developer settings → Personal access tokens →
生成一个带 `repo` + `workflow` 的 token → 给辰换上 → 辰把这个文件移到 `.github/workflows/` 推上去。
在那之前 APK 在 VPS 上本地打（见 `tool/android_native_plan_0908.md`）。
