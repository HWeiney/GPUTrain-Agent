# 阶段二 Runner：本地构建记录与目标机验收步骤

基线：`25b216ac`。本记录将本机可验证的构建与 `/data/hanw/hw_code/gputrain-test` 上尚未执行的 Linux 验收分开。只有完成下文目标机检查、填写实际命令 ID/PID/时间/结果后，才能宣布阶段二通过。不要把注册凭据、Runner 密钥、完整签名、管理员 Token 或数据库密码写入本文件、终端记录或 Git。

## 本地准备与证据

目标产物：`backend/ruoyi-train-runner/target/ruoyi-train-runner-3.9.2.jar`，无秘密包 `deploy/runner/build/gputrain-runner-bundle.tar.gz`。包内仅有 `runner/ruoyi-train-runner.jar`、`runner/gputrain-runner.service`、`runner/runner.properties.example`。它不含 `.env`、凭据或数据目录。

2026-10-04 在 macOS 本地运行：

```bash
mvn -f backend/pom.xml clean test
mvn -f backend/pom.xml clean package
test -f backend/ruoyi-train-runner/target/ruoyi-train-runner-3.9.2.jar
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env.example -f deploy/compose.yaml config -q
git diff --check
deploy/runner/build-bundle.sh
tar -tf deploy/runner/build/gputrain-runner-bundle.tar.gz
```

结果：在允许本地测试端口的环境中，`mvn clean test` 和随后执行的 `mvn clean package` 均退出 0，最终 JAR 存在；初次在受限沙箱中运行 Maven 时，13 个需绑定回环 HTTP 端口的测试被系统拒绝。Vitest 1/1 通过，Vite 6.4.1 转换 2642 个模块并生成生产构建；Compose 结构检查退出 0；`git diff --check` 退出 0。Compose 此处只用仓库的占位 `.env.example` 解析结构，不证明真实 `.env` 的值或服务连通性。远程 Linux 项目当前状态：**待执行**。

`build-bundle.sh` 每次创建全新临时 staging，只从最终 target JAR、受版本控制的 unit 和配置样例复制三个显式文件；它使用 `COPYFILE_DISABLE=1` 和 `tar --no-xattrs`，避免 macOS AppleDouble 文件和扩展属性进入 Linux 部署包，并在退出前核对成员列表。内容在目标 Linux 的 GNU tar 中精确核对为上述三个文件且无元数据警告；包成员名、JAR 成员名与两个文本配置文件经过 `rg` 检查，未命中 `.env`、私钥、凭据文件、数据目录或典型明文秘密模式。该静态扫描不替代目标环境的秘密管理检查。包 SHA-256：`b21cfdf61361d4a99b3df2ec3138658b626e3d7cd13573e46b95f523a5770241`。

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
printf '%s  %s\n' 'b21cfdf61361d4a99b3df2ec3138658b626e3d7cd13573e46b95f523a5770241' "$ROOT/gputrain-runner-bundle.tar.gz" | sha256sum -c -
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

首次重启前，控制面只靠命令上报更新 `train_runner_command`；进程表由恢复后的 `reconcile` 写入，此时不要求 `train_runner_process` 有行。先在控制面只读查询命令状态及 `result` 中的退出码，预期 success 为 `SUCCEEDED/0`、failure 为 `FAILED/17`、sleep 为 `RUNNING`。以下在控制面终端运行，先以注册响应中的数字设置 `RUNNER_ID`：

```bash
mysql --database=gputrain --batch --execute="SELECT command_uuid, command_type, status, attempt_count, JSON_UNQUOTE(JSON_EXTRACT(result, '$.exitCode')) AS reported_exit_code FROM train_runner_command WHERE runner_id=$RUNNER_ID ORDER BY created_at;"
```

在目标机按命令 UUID 读取本地状态文件，核对状态、PID、启动时间、退出码与日志路径。核对 success 的 stdout 恰为 `fixture success`、failure 的 stderr 恰为 `fixture failure`，且两者各有一个状态文件；不要上传整个日志目录。sleep 的本地状态应为 `RUNNING`，用精确 PID 的 `ps` 和本用户的 `pgrep` 检查它仍存活且没有同一 fixture 的重复进程：

```bash
SUCCESS_ID=$(jq -r '.data.commandUuid' "$ROOT/config/success-response.json")
FAILURE_ID=$(jq -r '.data.commandUuid' "$ROOT/config/failure-response.json")
SLEEP_ID=$(jq -r '.data.commandUuid' "$ROOT/config/sleep-response.json")
for ID in "$SUCCESS_ID" "$FAILURE_ID" "$SLEEP_ID"; do
  [[ "$ID" =~ ^[0-9a-fA-F-]{36}$ ]]
  jq '{commandId,pid,processStartedAt,executable,argumentsSha256,status,exitCode,stdoutPath,stderrPath}' "$ROOT/.gputrain/state/$ID.json"
done
jq -e '.status == "SUCCEEDED" and .exitCode == 0' "$ROOT/.gputrain/state/$SUCCESS_ID.json" >/dev/null
jq -e '.status == "FAILED" and .exitCode == 17' "$ROOT/.gputrain/state/$FAILURE_ID.json" >/dev/null
jq -e '.status == "RUNNING"' "$ROOT/.gputrain/state/$SLEEP_ID.json" >/dev/null
rg -x 'fixture success' "$(jq -r '.stdoutPath' "$ROOT/.gputrain/state/$SUCCESS_ID.json")"
rg -x 'fixture failure' "$(jq -r '.stderrPath' "$ROOT/.gputrain/state/$FAILURE_ID.json")"
SLEEP_PID=$(jq -r '.pid' "$ROOT/.gputrain/state/$SLEEP_ID.json")
[[ "$SLEEP_PID" =~ ^[0-9]+$ ]]
ps -p "$SLEEP_PID" -o pid=,lstart=,args=
MATCHING_PID=$(pgrep -u hanw -f 'com\.ruoyi\.train\.runner\.fixture\.FixtureMain --mode sleep --seconds 300')
test "$MATCHING_PID" = "$SLEEP_PID"
```

记录 sleep 的 PID、状态文件 `processStartedAt`、`ps` 结果和 `pgrep` 计数，然后仅重启 Runner：

```bash
systemctl --user restart gputrain-runner.service
systemctl --user is-active gputrain-runner.service
test "$(pgrep -u hanw -f 'com\.ruoyi\.train\.runner\.fixture\.FixtureMain --mode sleep --seconds 300')" = "$SLEEP_PID"
find "$ROOT/.gputrain/state" -maxdepth 1 -type f -name '*.json' -print
```

重启后才从 `train_runner_process` 查询该 sleep 命令。等待对账完成，验证进程行出现、PID 与启动时间和重启前的本地状态一致，仍只有一个匹配进程，命令和进程状态均为 `RUNNING`：

```bash
mysql --database=gputrain --batch --execute="SELECT c.command_uuid, c.status AS command_status, p.pid, p.process_started_at, p.status AS process_status, p.exit_code FROM train_runner_command c LEFT JOIN train_runner_process p ON p.command_id=c.command_id WHERE c.runner_id=$RUNNER_ID AND c.command_uuid='$SLEEP_ID';"
```

然后请求停止并重复一次，确认两个返回的停止命令 UUID 一致，原命令与进程最终均为 `STOPPED`：

```bash
SLEEP_ID=$(jq -r '.data.commandUuid' "$ROOT/config/sleep-response.json")
for TRY in 1 2; do
  curl --silent --show-error --fail-with-body -H @"$ROOT/config/admin-header" \
    -X POST "$CONTROL_URL/train/runners/$RUNNER_ID/commands/$SLEEP_ID/stop" \
    -o "$ROOT/config/stop-$TRY-response.json"
  jq -e '.code == 200' "$ROOT/config/stop-$TRY-response.json" >/dev/null
  jq -r '.data.commandUuid' "$ROOT/config/stop-$TRY-response.json"
done
STOP_ID_1=$(jq -r '.data.commandUuid' "$ROOT/config/stop-1-response.json")
STOP_ID_2=$(jq -r '.data.commandUuid' "$ROOT/config/stop-2-response.json")
test "$STOP_ID_1" = "$STOP_ID_2"
```

等待 Runner 处理 STOP 后，在控制面终端重复以下只读查询，直至原 START 命令与进程行均为 `STOPPED`；再回目标机检查原 PID 已退出：

```bash
mysql --database=gputrain --batch --execute="SELECT c.command_uuid, c.status AS command_status, p.status AS process_status FROM train_runner_command c LEFT JOIN train_runner_process p ON p.command_id=c.command_id WHERE c.runner_id=$RUNNER_ID AND c.command_uuid='$SLEEP_ID';"
```

```bash
if kill -0 "$SLEEP_PID" 2>/dev/null; then printf 'Sleep PID is still present; stop acceptance failed\n' >&2; exit 1; fi
find "$ROOT" -xdev -type f -print
```

`find` 的列表用于检查本次部署及执行创建的文件均在允许根目录；安装在 `~/.config/systemd/user/` 的 unit 是明确的用户级服务配置例外。要证明“根目录外无新增文件”，还需由操作者对目标机已知的相关目录做部署前后清单对比；仅一次 `find` 不足以证明。检查完毕后可移除 `config/` 中的一次性注册请求、响应和管理员认证头，保留 `runner.properties` 与 `runner.secret`；删除前确认无后续验收需要这些文件。

## 生命周期与安全停机

`KillMode=process` 是重启恢复测试的必要条件，但 `systemctl --user stop` 也只结束 Runner，不会结束它启动的 fixture。Runner 退出时内存超时计时器消失；重启恢复进程后当前实现**不会重建该计时器**。因此不要把 `timeoutSeconds` 当作跨重启的强制停止保证。验收结束或正式停机时，先通过控制面对所有 `RUNNING` 的 `START_PROCESS` 命令逐一请求 STOP，确认每条命令的最终状态、对应 PID 已退出，再停止及禁用 unit。不要直接停止仍有子进程的 unit，也不要使用 `pkill java`。

控制面可用时，先查询全部仍运行的 START 命令并对每个 UUID 调用前述 `POST /train/runners/{runnerId}/commands/{id}/stop`。以下只读检查在控制面终端执行，返回计数为 0 且目标机逐个 PID 均已退出，才可在目标机执行后两条命令：

```bash
mysql --database=gputrain --batch --execute="SELECT command_uuid, status FROM train_runner_command WHERE runner_id=$RUNNER_ID AND command_type='START_PROCESS' AND status='RUNNING';"
mysql --database=gputrain --batch --skip-column-names --execute="SELECT COUNT(*) FROM train_runner_command WHERE runner_id=$RUNNER_ID AND command_type='START_PROCESS' AND status='RUNNING';"
```

```bash
systemctl --user stop gputrain-runner.service
systemctl --user disable gputrain-runner.service
```

如果控制面不可用且必须紧急停机，先保留 Runner 运行状态，针对 `$ROOT/.gputrain/state/` 中每个 `RUNNING` 条目由 `hanw` 核对 `commandId`、PID、`processStartedAt`、可执行文件、工作目录和参数摘要；任何一项不符、缺失或无法读取时立即停止人工处置，保留现场等待控制面恢复。以下仅对指定状态文件执行身份校验，全部匹配后用 pidfd 向该进程发送 TERM；它不按进程名批量杀进程，不自动升级为 KILL。需 Python 3.9+ 和 Linux pidfd 支持，缺少时会拒绝执行：

```bash
STATE_FILE="$ROOT/.gputrain/state/$SLEEP_ID.json"
python3 - "$ROOT" "$STATE_FILE" <<'PY'
import datetime, hashlib, json, os, pathlib, select, signal, struct, sys
root = pathlib.Path(sys.argv[1]).resolve(strict=True)
state_file = pathlib.Path(sys.argv[2])
if state_file.parent.resolve(strict=True) != root / '.gputrain/state':
    raise SystemExit('State file is outside the allowed root')
state = json.loads(state_file.read_text())
if state_file.name != state['commandId'] + '.json' or state['status'] != 'RUNNING':
    raise SystemExit('State identity or status does not match')
pid = state['pid']
if not isinstance(pid, int) or pid <= 0:
    raise SystemExit('Invalid PID')
fd = os.pidfd_open(pid)
proc = pathlib.Path('/proc') / str(pid)
if pathlib.Path(os.readlink(proc / 'exe')).resolve(strict=True) != pathlib.Path(state['executable']).resolve(strict=True):
    raise SystemExit('Executable mismatch')
if pathlib.Path(os.readlink(proc / 'cwd')).resolve(strict=True) != pathlib.Path(state['workingDirectory']).resolve(strict=True):
    raise SystemExit('Working directory mismatch')
cmdline = (proc / 'cmdline').read_bytes().split(b'\0')
if not cmdline or cmdline[-1] != b'' or len(cmdline) < 2:
    raise SystemExit('Command line unavailable')
digest = hashlib.sha256()
for arg in cmdline[1:-1]:
    digest.update(struct.pack('>I', len(arg)))
    digest.update(arg)
if digest.hexdigest() != state['argumentsSha256']:
    raise SystemExit('Arguments mismatch')
fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
ticks = int(fields[19])
boot = next(int(line.split()[1]) for line in pathlib.Path('/proc/stat').read_text().splitlines() if line.startswith('btime '))
hz = os.sysconf('SC_CLK_TCK')
observed = boot + ticks / hz
saved = datetime.datetime.fromisoformat(state['processStartedAt'].replace('Z', '+00:00')).timestamp()
if abs(observed - saved) > 1 / hz:
    raise SystemExit('Process start time mismatch')
signal.pidfd_send_signal(fd, signal.SIGTERM, None, 0)
poller = select.poll()
poller.register(fd, select.POLLIN)
if not poller.poll(5000):
    raise SystemExit('Process did not exit after TERM; do not stop the unit yet')
print('Verified fixture process exited:', pid)
PY
```

对所有本地 `RUNNING` 状态文件分别核验并确认无活进程后，可停止 unit 以隔离 Runner；控制面恢复后仍需完成对账并核对最终状态。人工 TERM 不能替代 API 的幂等 STOP 验收，也不能被记为阶段二通过。若无法核对身份或某个进程未退出，不执行 `systemctl stop/disable`，请求熟悉该主机的操作者介入。

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
