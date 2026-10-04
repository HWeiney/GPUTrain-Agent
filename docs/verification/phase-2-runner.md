# 阶段二 Runner：本地构建记录与目标机验收步骤

基线：`25b216ac`。本记录将本机可验证的构建与 `/data/hanw/hw_code/gputrain-test` 上尚未执行的 Linux 验收分开。只有完成下文目标机检查、填写实际命令 ID/PID/时间/结果后，才能宣布阶段二通过。不要把注册凭据、Runner 密钥、完整签名、管理员 Token 或数据库密码写入本文件、终端记录或 Git。

## 本地准备与证据

目标产物：`backend/ruoyi-train-runner/target/ruoyi-train-runner-3.9.2.jar`，无秘密包 `deploy/runner/build/gputrain-runner-bundle.tar.gz`。包内仅有 `runner/ruoyi-train-runner.jar`、`runner/gputrain-runner.service`、`runner/runner.properties.example`。它不含 `.env`、凭据或数据目录。

2026-10-04 在 macOS 本地运行：

```bash
mvn -f backend/pom.xml clean package
mvn -f backend/pom.xml clean test
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env.example -f deploy/compose.yaml config -q
git diff --check
tar -tf deploy/runner/build/gputrain-runner-bundle.tar.gz
```

结果：`mvn clean package` 和 `mvn clean test` 均退出 0；初次在受限沙箱中运行时，13 个需绑定回环 HTTP 端口的测试被系统拒绝，随后在允许该本地端口的环境中重跑上述两条 Maven 命令成功。Vitest 1/1 通过，Vite 6.4.1 转换 2642 个模块并生成生产构建；Compose 结构检查退出 0；`git diff --check` 退出 0。Compose 此处只用仓库的占位 `.env.example` 解析结构，不证明真实 `.env` 的值或服务连通性。远程 Linux 项目当前状态：**待执行**。

包内容经 `tar -tzf` 精确核对为上述三个文件；包成员名、JAR 成员名与两个文本配置文件经过 `rg` 检查，未命中 `.env`、私钥、凭据文件、数据目录或典型明文秘密模式。该静态扫描不替代目标环境的秘密管理检查。包 SHA-256：`7a5ce58b5bea12ff30c9252fccb99a6146f4175152c4f79c3e57ee791e0ae540`。

## 目标机前提

以下命令由 `hanw` 在目标 Linux 终端执行。目标根目录只能是 `/data/hanw/hw_code/gputrain-test`。控制面须已部署、能从目标机通过受 Java 信任的 HTTPS 证书访问，并加载 `backend/sql/gputrain_runner.sql`；控制面进程需从非 Git 秘密源取得 `RUNNER_CREDENTIAL_MASTER_KEY`（Base64 编码的 32 字节）和 `RUNNER_ENROLLMENT_PEPPER`。目标机需有 Java 17、`jq`、`curl`、`uuidgen`、`systemd --user`，并确认 `/usr/bin/java` 可执行。用户通过已有、获准的文件传输方式把 bundle 放到该根目录。这里不要求 SSH 密码或私钥，也不预设已连接目标机。

```bash
set -euo pipefail
ROOT=/data/hanw/hw_code/gputrain-test
test "$(id -un)" = hanw
test -d "$ROOT"
test "$(realpath "$ROOT")" = "$ROOT"
test -x /usr/bin/java
test -f "$ROOT/gputrain-runner-bundle.tar.gz"
printf '%s  %s\n' '7a5ce58b5bea12ff30c9252fccb99a6146f4175152c4f79c3e57ee791e0ae540' "$ROOT/gputrain-runner-bundle.tar.gz" | sha256sum -c -
tar -tzf "$ROOT/gputrain-runner-bundle.tar.gz"
tar -xzf "$ROOT/gputrain-runner-bundle.tar.gz" -C "$ROOT"
install -d -m 700 "$ROOT/config" "$HOME/.config/systemd/user"
install -m 600 "$ROOT/runner/runner.properties.example" "$ROOT/config/runner.properties"
install -m 644 "$ROOT/runner/gputrain-runner.service" "$HOME/.config/systemd/user/gputrain-runner.service"
```

在 `runner.properties` 中替换 `controlPlaneUrl`、注册后的 `runnerId`，并确认 `executableAllowlist=/usr/bin/java` 在目标机可解析。示例不含密钥。注册凭据只在一次注册中使用；Runner 程序当前没有注册命令行入口，需通过以下控制面 API 完成注册，再启动服务。

## 注册与首次启动

在安全终端设置非秘密的 `CONTROL_URL`（HTTPS origin）、`OWNER_USER_ID`，并将管理员认证头写入 `$ROOT/config/admin-header`，格式为 `Authorization: Bearer <token>`。该文件、下述请求/响应文件均须 `600`，不要在 shell 命令行中直接输入 token。所有 JSON 文件都留在允许根目录的 `config/` 下。

```bash
umask 077
CONTROL_URL=https://control.example.test
OWNER_USER_ID=1
${EDITOR:-vi} "$ROOT/config/admin-header"
chmod 600 "$ROOT/config/admin-header"
jq -n --argjson owner "$OWNER_USER_ID" --arg root "$ROOT" \
  --arg expiry "$(date -u -d '+15 minutes' +%Y-%m-%dT%H:%M:%SZ)" \
  --arg key "$(uuidgen)" \
  '{ownerUserId:$owner,runnerName:"hanw-phase2-runner",linuxUsername:"hanw",allowedRoot:$root,expiresAt:$expiry,idempotencyKey:$key}' \
  > "$ROOT/config/enrollment-request.json"
curl --silent --show-error --fail-with-body -H @"$ROOT/config/admin-header" \
  -H 'Content-Type: application/json' --data-binary @"$ROOT/config/enrollment-request.json" \
  "$CONTROL_URL/train/runners/enrollments" -o "$ROOT/config/enrollment-response.json"
jq -e '.code == 200 and (.data.enrollmentId | type == "number")' "$ROOT/config/enrollment-response.json" >/dev/null
jq '{enrollmentId:.data.enrollmentId,enrollmentToken:.data.plaintextToken,runnerName:.data.runnerName,linuxUsername:.data.linuxUsername}' \
  "$ROOT/config/enrollment-response.json" > "$ROOT/config/register-request.json"
curl --silent --show-error --fail-with-body -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/config/register-request.json" \
  "$CONTROL_URL/runner-api/v1/register" -o "$ROOT/config/register-response.json"
jq -e '.runnerId and .secret' "$ROOT/config/register-response.json" >/dev/null
jq -r '.secret' "$ROOT/config/register-response.json" > "$ROOT/config/runner.secret"
chmod 600 "$ROOT/config/runner.secret"
RUNNER_ID=$(jq -r '.runnerId' "$ROOT/config/register-response.json")
[[ "$RUNNER_ID" =~ ^[0-9]+$ ]]
sed -i "s/^runnerId=.*/runnerId=$RUNNER_ID/" "$ROOT/config/runner.properties"
sed -i "s|^controlPlaneUrl=.*|controlPlaneUrl=$CONTROL_URL|" "$ROOT/config/runner.properties"
printf 'Registered Runner ID: %s\n' "$RUNNER_ID"
```

确认 `runner.properties` 中的 `credentialFile` 指向 `$ROOT/config/runner.secret`。在注册请求文件仍存在时再次发送同一请求，验证一次性消费；此处故意不使用 `--fail-with-body`，因为预期为协议错误：

```bash
curl --silent --show-error -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/config/register-request.json" \
  "$CONTROL_URL/runner-api/v1/register" -o "$ROOT/config/register-reuse-response.json"
jq -e '.errorCode == "ENROLLMENT_ALREADY_USED"' "$ROOT/config/register-reuse-response.json" >/dev/null
stat -c '%a %U %n' "$ROOT/config/runner.secret"
systemd-analyze --user verify "$HOME/.config/systemd/user/gputrain-runner.service"
systemctl --user daemon-reload
systemctl --user enable --now gputrain-runner.service
systemctl --user is-active gputrain-runner.service
```

`stat` 必须显示 `600 hanw`，`systemd-analyze` 无错误。用控制面数据库只读查询确认 `train_runner.last_seen_at` 在启动后更新，Redis 在线键 TTL 为正；记录实际时间和 TTL。不要输出密钥列。以下在可访问 MySQL/Redis 的控制面终端运行，使用该终端已有的认证配置，不把密码写进命令；`RUNNER_ID` 为注册响应显示的数字：

```bash
mysql --database=gputrain --batch --execute="SELECT runner_id, status, last_seen_at FROM train_runner WHERE runner_name='hanw-phase2-runner';"
redis-cli TTL "gputrain:runner:online:$RUNNER_ID"
```

## 安全 fixture 验收

下面的 `RUNNER_ID` 必须来自注册响应。管理员 API 返回 RuoYi `AjaxResult`；每次请求后先检查 `.code == 200`，再记录 `.data.commandUuid`。所有 `ProcessSpec` 使用已在白名单内的 Java 可执行文件、根目录工作目录、空环境变量和独立参数数组。

```bash
RUNNER_ID=$(jq -r '.runnerId' "$ROOT/config/register-response.json")
[[ "$RUNNER_ID" =~ ^[0-9]+$ ]]
JAVA=$(realpath /usr/bin/java)
for MODE in success failure sleep; do
  jq -n --arg root "$ROOT" --arg java "$JAVA" --arg mode "$MODE" \
    --arg key "phase2-$MODE-$(uuidgen)" \
    '{workingDirectory:$root,executable:$java,arguments:(if $mode == "sleep" then ["-cp",($root+"/runner/ruoyi-train-runner.jar"),"com.ruoyi.train.runner.fixture.FixtureMain","--mode","sleep","--seconds","300"] else ["-cp",($root+"/runner/ruoyi-train-runner.jar"),"com.ruoyi.train.runner.fixture.FixtureMain","--mode",$mode] end),environment:{},timeoutSeconds:360,idempotencyKey:$key}' \
    > "$ROOT/config/$MODE-request.json"
  curl --silent --show-error --fail-with-body -H @"$ROOT/config/admin-header" \
    -H 'Content-Type: application/json' --data-binary @"$ROOT/config/$MODE-request.json" \
    "$CONTROL_URL/train/runners/$RUNNER_ID/fixture-commands" \
    -o "$ROOT/config/$MODE-response.json"
  jq -e '.code == 200 and (.data.commandUuid | type == "string")' "$ROOT/config/$MODE-response.json" >/dev/null
  jq -r '.data.commandUuid' "$ROOT/config/$MODE-response.json"
done
```

在控制面数据库用只读查询查看这三个 UUID 对应的 `train_runner_command.status`、`train_runner_process.pid`、`exit_code`、`process_started_at`；预期 success 为 `SUCCEEDED/0`、failure 为 `FAILED/17`、sleep 为 `RUNNING`。以下查询在控制面终端运行，先以注册响应中的数字设置 `RUNNER_ID`：

```bash
mysql --database=gputrain --batch --execute="SELECT c.command_uuid, c.command_type, c.status, c.attempt_count, p.pid, p.process_started_at, p.status AS process_status, p.exit_code FROM train_runner_command c LEFT JOIN train_runner_process p ON p.command_id=c.command_id WHERE c.runner_id=$RUNNER_ID ORDER BY c.created_at;"
```

在目标机 `.gputrain/logs/` 中核对 success 的 `fixture success` 和 failure 的 `fixture failure`，并通过相应状态文件定位日志路径。短时命令的执行记录各应只有一条。不要上传整个日志目录；只记录这些非敏感行和计数。

在 sleep 仍运行时记录其 PID、启动时间和 `ps` 行数，然后仅重启 Runner：

```bash
systemctl --user restart gputrain-runner.service
systemctl --user is-active gputrain-runner.service
find "$ROOT/.gputrain/state" -maxdepth 1 -type f -name '*.json' -print
```

再次查询该 sleep 命令：PID、启动时间不变，仍只有一个匹配进程；状态保持 `RUNNING`。然后请求停止并重复一次，确认两个返回的停止命令 UUID 一致，原命令与进程最终均为 `STOPPED`：

```bash
SLEEP_ID=$(jq -r '.data.commandUuid' "$ROOT/config/sleep-response.json")
for TRY in 1 2; do
  curl --silent --show-error --fail-with-body -H @"$ROOT/config/admin-header" \
    -X POST "$CONTROL_URL/train/runners/$RUNNER_ID/commands/$SLEEP_ID/stop" \
    -o "$ROOT/config/stop-$TRY-response.json"
  jq -e '.code == 200' "$ROOT/config/stop-$TRY-response.json" >/dev/null
  jq -r '.data.commandUuid' "$ROOT/config/stop-$TRY-response.json"
done
find "$ROOT" -xdev -type f -print
```

`find` 的列表用于检查本次部署及执行创建的文件均在允许根目录；安装在 `~/.config/systemd/user/` 的 unit 是明确的用户级服务配置例外。要证明“根目录外无新增文件”，还需由操作者对目标机已知的相关目录做部署前后清单对比；仅一次 `find` 不足以证明。检查完毕后可移除 `config/` 中的一次性注册请求、响应和管理员认证头，保留 `runner.properties` 与 `runner.secret`；删除前确认无后续验收需要这些文件。

## 待填写的远程证据

| 检查 | 实测时间 / ID / 结果 |
|---|---|
| 一次注册与重用错误码 | 待执行 |
| 密钥文件权限、unit 校验、心跳在线 | 待执行 |
| success：命令 UUID、退出码、stdout、进程数 | 待执行 |
| failure：命令 UUID、退出码、stderr、进程数 | 待执行 |
| sleep：重启前后 PID / 启动时间 / 进程数 | 待执行 |
| 停止及重复停止的最终状态 | 待执行 |
| 根目录内文件清单与目录外基线对比 | 待执行 |

当前不得写“Linux 已通过”。阶段三 GPU 发现、调度和真实训练执行也不在本验收范围内。
