# GPUTrain 首次训练测试

这个示例用 PyTorch 在 NVIDIA GPU 上训练一个小型分类器。数据在 GPU 上即时生成，不下载数据集。默认运行 100 步，约有 20 秒的主动等待时间，便于在 GPUTrain 中观察启动、日志和结束状态。每次运行都在 `examples/gpu-smoke/runs/` 下创建独立目录，写入 `metrics.jsonl`、`model.pt` 和 `summary.json`；这些产物不会提交到 Git。

## 1. 在服务器准备代码与环境

以 `hanw` 登录 GPU 服务器，把本仓库的 `dev` 分支克隆到目标目录（若已克隆则更新该分支）：

```bash
mkdir -p /data/hanw/hw_code
git clone -b dev git@github.com:HWeiney/GPUTrain-Agent.git /data/hanw/hw_code/gputrain-test
cd /data/hanw/hw_code/gputrain-test
nvidia-smi
conda env create -f examples/gpu-smoke/environment.yml
```

如果服务器尚无 `conda`，先由服务器管理员安装 Miniconda 或 Miniforge。根据 `nvidia-smi` 中的驱动与 GPU 型号，在 [PyTorch 官方安装页](https://pytorch.org/get-started/locally/)选择 Linux、Pip 和可用的 CUDA 构建。例如驱动支持 CUDA 12.6 且 GPU 受该构建支持时，可安装经过固定版本验证的示例依赖：

```bash
conda run -n gputrain-smoke python -m pip install torch==2.8.0 --index-url https://download.pytorch.org/whl/cu126
conda run -n gputrain-smoke python examples/gpu-smoke/train.py --check
conda run -n gputrain-smoke python -c 'import sys; print(sys.executable)'
```

最后一条命令打印的**绝对路径**是平台表单中的“已有环境中的可执行文件”。如果 `--check` 报 CUDA 不可用，先核对驱动、所选 wheel 和 GPU 可见性；不要继续提交训练。

本地手动试跑可用：

```bash
conda run -n gputrain-smoke --no-capture-output python examples/gpu-smoke/train.py --steps 20 --step-delay 0
```

## 2. 在 GPUTrain 中启动

1. 在“服务器连接”恢复并保存 `hanw` 账户。允许的工作目录填写 `/data/hanw/hw_code/gputrain-test`。先测试 SSH 连接，再保存；在服务器详情中点“立即探测”，确认 GPU 和 `systemd --user` 能力。
2. 打开“实验”，创建一个实验，再点“配置并启动训练”。选择这台服务器和 `hanw` 账户。
3. 按下面填写 Run 表单：

   | 字段 | 内容 |
   | --- | --- |
   | 项目工作目录 | `/data/hanw/hw_code/gputrain-test` |
   | 已有环境中的可执行文件 | 上一步打印的 Conda 环境 Python 绝对路径 |
   | 训练入口文件 | `/data/hanw/hw_code/gputrain-test/examples/gpu-smoke/train.py` |
   | GPU | **至少选择一块**已探测到的 NVIDIA GPU |
   | 参数 | 可留空；若要缩短测试，每行分别填 `--steps`、`20`、`--step-delay`、`0` |
   | 环境变量 | 留空 |

4. 点击“检查远端环境”，查看路径、`systemd --user` 和 GPU 的预检结果。预检通过后点“确认并启动训练”。在实验的运行记录中查看状态与日志；正常结束时应看到 `Training completed`。
5. 在服务器上检查 `examples/gpu-smoke/runs/<运行目录>/summary.json` 和 `metrics.jsonl`。每次运行都会生成一个新目录。

平台会为选中的 GPU 设置 `CUDA_VISIBLE_DEVICES`。如果未选 GPU，脚本会明确报错退出，以免一次 CPU 运行被误认为 GPU 测试。GPUTrain 的远端预检还要求所选 Linux 用户的 `systemd --user` 可用；若报 `RUN_SYSTEMD_UNAVAILABLE`，先请服务器管理员检查用户会话与 linger 配置。
