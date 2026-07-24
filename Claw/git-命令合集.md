# Git 命令合集（实战速查）

> 面向日常开发与分支治理，按场景分类。带 ⭐ 的是高风险/易误用命令，执行前务必确认。

---

## 一、配置与初始化

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
git config --global core.editor "vim"          # 默认编辑器
git config --global alias.co checkout           # 设置别名
git config --global alias.lg "log --oneline --graph --all"

git init                                        # 初始化本地仓库
git clone <url>                                 # 克隆远程仓库
git clone <url> <dir>                           # 克隆到指定目录
```

---

## 二、日常提交工作流

```bash
git status                                      # 查看工作区状态
git add <file>                                  # 添加单个文件
git add .                                       # 添加所有改动（谨慎使用）
git add -p                                      # 交互式分块添加（推荐）
git commit -m "msg"                             # 提交
git commit -am "msg"                            # 自动 add 已跟踪文件并提交
git commit --amend                              # 修改最近一次提交（未推送时）
git commit --amend --no-edit                    # 仅补文件，不改信息
```

---

## 三、查看历史与状态

```bash
git log --oneline -10                          # 简洁历史（最近10条）
git log --oneline --graph --all                # 图形化全部分支
git log -p <file>                              # 某文件的改动历史（含 diff）
git log --author="name" --since="2026-07-01"   # 按作者/时间过滤
git show <commit>                              # 查看某次提交详情
git diff                                       # 工作区 vs 暂存区
git diff --cached                              # 暂存区 vs 最新提交
git diff <a> <b>                               # 两次提交的差异
git blame <file>                               # 逐行查看作者（追责/溯源）
```

---

## 四、分支管理

```bash
git branch                                     # 列出本地分支
git branch -a                                  # 列出全部分支（含远程）
git branch <name>                             # 新建分支
git checkout <name>                           # 切换分支
git checkout -b <name>                        # 新建并切换
git switch <name>                             # 切换（新版推荐）
git switch -c <name>                          # 新建并切换
git branch -m <old> <new>                     # 重命名分支
git branch -d <name>                          # 删除已合并分支
git branch -D <name>                          # ⭐ 强制删除未合并分支
git merge <name>                              # 合并指定分支到当前
git merge --no-ff <name>                      # 保留 merge 节点（禁用快进）
```

---

## 五、撤销与回退 ⭐

```bash
git checkout -- <file>                        # 丢弃工作区某文件改动
git restore <file>                            # 同上（新版推荐）
git restore --staged <file>                   # 取消暂存（保留改动）
git reset                                     # 取消全部暂存（保留改动）
git reset --soft <commit>                     # 回退提交，保留改动在暂存区
git reset --mixed <commit>                    # 回退提交，改动留在工作区（默认）
git reset --hard <commit>                     # ⭐⭐ 回退并提交+工作区全部丢弃

git revert <commit>                           # 生成一个反向提交（安全，可推送）
git revert <commit>..<commit>                 # 区间反转
```

> 📌 你刚用的就是 `git reset --hard 747f695…`：本地指针与文件一并回到目标提交，被丢弃的 `8723218` 不再出现在本地历史。
> **reset vs revert**：`reset` 改写历史（需强推），`revert` 不改写历史（推荐用于已推送的提交，除非确需彻底清除）。

---

## 六、远程操作

```bash
git remote -v                                  # 查看远程地址
git remote add origin <url>                    # 关联远程
git fetch origin                               # 拉取远程更新（不合并）
git pull                                       # 拉取并合并（= fetch + merge）
git pull --rebase                              # 拉取并以变基方式整合（历史更干净）
git push origin <branch>                       # 推送到远程分支
git push -u origin <branch>                    # 首次推送并建立跟踪
git push --force origin <branch>               # ⭐⭐ 强制覆盖远程（危险）
git push --force-with-lease origin <branch>   # ⭐ 安全强推：若他人已推送则拒绝
git push origin --delete <branch>              # 删除远程分支
```

> 📌 你刚用的是 `git push --force-with-lease origin erp_1.3.5_20260728_merge_order_dev`，比 `--force` 安全：一旦远程有他人新提交会被拒绝，避免误覆盖。

---

## 七、暂存（Stash）

```bash
git stash                                      # 暂存当前改动
git stash push -m "msg"                        # 带备注暂存
git stash list                                 # 查看暂存列表
git stash pop                                  # 恢复最近一次并删除
git stash apply                                # 恢复最近一次（保留在列表）
git stash drop stash@{0}                       # 删除指定暂存
git stash clear                                # 清空所有暂存
```

---

## 八、变基（Rebase）

```bash
git rebase <branch>                            # 把当前分支变基到目标分支
git rebase -i <commit>                         # 交互式变基（合并/改写提交）
git rebase --continue                          # 解决冲突后继续
git rebase --abort                             # 放弃变基，回到操作前
git pull --rebase                             # 拉取时变基（避免多余 merge 节点）
```

> ⭐ 变基会改写提交哈希，已推送的分支变基后同样需要 `--force-with-lease`。

---

## 九、挑选与补丁

```bash
git cherry-pick <commit>                       # 把某次提交应用到当前分支
git cherry-pick <a>..<b>                       # 区间挑选
git cherry-pick --abort                        # 放弃挑选
git format-patch <commit>                      # 生成补丁文件
git apply <patch>                              # 应用补丁
```

---

## 十、标签（Tag）

```bash
git tag                                        # 列出标签
git tag v1.0.0                                 # 打轻量标签
git tag -a v1.0.0 -m "release"                 # 打附注标签
git push origin v1.0.0                         # 推送单个标签
git push origin --tags                         # 推送所有标签
git tag -d v1.0.0                              # 删除本地标签
git push origin --delete v1.0.0                # 删除远程标签
```

---

## 十一、找回与清理

```bash
git reflog                                     # ⭐ 查看 HEAD 变动记录（误删救命）
git reflog expire --expire=now --all           # 清理 reflog
git fsck --lost-found                          # 查找悬空对象（找回丢失提交）
git clean -fd                                  # ⭐ 删除未跟踪文件/目录
git clean -fdx                                 # ⭐ 连 .gitignore 忽略的也删
git gc                                         # 仓库垃圾回收/压缩
```

> 💡 误执行 `reset --hard` 后可用 `git reflog` 找到旧 HEAD，再 `reset --hard <旧SHA>` 救回。

---

## 十二、查找与调试

```bash
git grep "keyword"                             # 在版本库中搜索文本
git log -S "keyword"                           # 查找引入/移除某字符串的提交
git bisect start                               # 二分定位引入 bug 的提交
git bisect bad                                 # 标记当前为坏
git bisect good <commit>                       # 标记某提交为好
git bisect run <test.sh>                       # 自动二分
```

---

## 附：本次回退用到的完整命令

```bash
# 1) 本地硬回退
cd /d c:/D/android_project/erp-pda
git reset --hard 747f69511a22d2dc8e40bd0e81ddde996d90788f

# 2) 校验
git --no-pager log --oneline -3
git --no-pager status -sb

# 3) 安全强推同步远程
git push --force-with-lease origin erp_1.3.5_20260728_merge_order_dev

# 团队成员重新对齐（会丢弃本地基于 8723218 的未推送改动）
git fetch origin
git reset --hard origin/erp_1.3.5_20260728_merge_order_dev
```
