#!/usr/bin/env python3
"""Small, self-contained CUDA training job for testing GPUTrain Run lifecycle."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a small classifier on synthetic GPU data")
    parser.add_argument("--check", action="store_true", help="verify CUDA and run one GPU operation")
    parser.add_argument("--steps", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--report-every", type=int, default=10)
    parser.add_argument("--step-delay", type=float, default=0.2, help="seconds to pause after each step")
    parser.add_argument("--output-dir", type=Path, default=Path("examples/gpu-smoke/runs"))
    args = parser.parse_args()
    if args.steps < 1 or args.batch_size < 1 or args.report_every < 1 or args.step_delay < 0:
        parser.error("steps, batch-size and report-every must be positive; step-delay must be nonnegative")
    return args


def main() -> int:
    args = parse_args()
    try:
        import torch
    except ImportError:
        print("PyTorch is missing. Install the CUDA wheel in the gputrain-smoke Conda environment.", file=sys.stderr)
        return 2

    if not torch.cuda.is_available():
        print(
            "CUDA is unavailable. Check nvidia-smi, the PyTorch CUDA wheel, and select a GPU in GPUTrain. "
            f"CUDA_VISIBLE_DEVICES={os.environ.get('CUDA_VISIBLE_DEVICES', '<unset>')!r}",
            file=sys.stderr,
        )
        return 2

    device = torch.device("cuda:0")
    gpu_name = torch.cuda.get_device_name(device)
    torch.manual_seed(20261020)
    torch.cuda.manual_seed_all(20261020)
    probe = torch.ones((64, 64), device=device) @ torch.ones((64, 64), device=device)
    torch.cuda.synchronize(device)
    if probe[0, 0].item() != 64.0:
        raise RuntimeError("GPU matrix multiplication produced an unexpected result")
    print(
        f"GPU check passed: {gpu_name}; torch={torch.__version__}; "
        f"torch CUDA={torch.version.cuda}; CUDA_VISIBLE_DEVICES={os.environ.get('CUDA_VISIBLE_DEVICES', '<unset>')}",
        flush=True,
    )
    if args.check:
        return 0

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    run_dir = args.output_dir.resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    metrics_file = run_dir / "metrics.jsonl"

    input_dim, hidden_dim, num_classes = 128, 256, 8
    teacher = torch.randn(input_dim, num_classes, device=device)
    model = torch.nn.Sequential(
        torch.nn.Linear(input_dim, hidden_dim),
        torch.nn.ReLU(),
        torch.nn.Linear(hidden_dim, num_classes),
    ).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.003)
    start = time.monotonic()
    print(f"Training started: steps={args.steps}, batch_size={args.batch_size}, output={run_dir}", flush=True)

    with metrics_file.open("w", encoding="utf-8") as metrics:
        for step in range(1, args.steps + 1):
            features = torch.randn(args.batch_size, input_dim, device=device)
            with torch.no_grad():
                labels = (features @ teacher).argmax(dim=1)
            logits = model(features)
            loss = torch.nn.functional.cross_entropy(logits, labels)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()

            if step == 1 or step % args.report_every == 0 or step == args.steps:
                torch.cuda.synchronize(device)
                record = {
                    "step": step,
                    "loss": round(float(loss.item()), 6),
                    "accuracy": round(float((logits.argmax(dim=1) == labels).float().mean().item()), 6),
                    "elapsed_seconds": round(time.monotonic() - start, 3),
                    "gpu_memory_mib": round(torch.cuda.max_memory_allocated(device) / 1048576, 1),
                }
                metrics.write(json.dumps(record) + "\n")
                metrics.flush()
                print(json.dumps(record), flush=True)
            if args.step_delay:
                time.sleep(args.step_delay)

    torch.save(model.state_dict(), run_dir / "model.pt")
    summary = {
        "run_id": run_id,
        "gpu_name": gpu_name,
        "torch_version": torch.__version__,
        "torch_cuda_version": torch.version.cuda,
        "steps": args.steps,
        "elapsed_seconds": round(time.monotonic() - start, 3),
        "last_loss": record["loss"],
        "last_accuracy": record["accuracy"],
        "metrics_file": str(metrics_file),
        "checkpoint_file": str(run_dir / "model.pt"),
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Training completed: {run_dir / 'summary.json'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
