#!/usr/bin/env python3
"""Observe index.lock syscalls without modifying the repository or its locks (Linux)."""
import argparse
import datetime
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys


def process_info(pid):
    info = {"pid": pid}
    for name in ("cmdline", "cwd"):
        try:
            p = Path(f"/proc/{pid}/{name}")
            info[name] = (p.read_bytes().decode(errors="replace").strip("\0").split("\0")
                          if name == "cmdline" else os.readlink(p))
        except OSError as error:
            info[name] = f"unavailable: {error.strerror}"
    try:
        status = Path(f"/proc/{pid}/status").read_text()
        info["ppid"] = int(next(line.split()[1] for line in status.splitlines() if line.startswith("PPid:")))
    except (OSError, StopIteration):
        info["ppid"] = None
    return info


def resolve_path(pid, directory_fd, filename):
    if os.path.isabs(filename):
        return os.path.normpath(filename)
    try:
        base = os.readlink(f"/proc/{pid}/cwd" if directory_fd == -100
                           else f"/proc/{pid}/fd/{directory_fd}")
        return os.path.normpath(os.path.join(base, filename))
    except OSError:
        return None



def trace_targets():
    """Select existing user applications/shells; exclude this collector and its ancestors."""
    excluded = set()
    pid = os.getpid()
    while pid > 1 and pid not in excluded:
        excluded.add(pid)
        try:
            status = Path(f"/proc/{pid}/status").read_text()
            pid = int(next(line.split()[1] for line in status.splitlines() if line.startswith("PPid:")))
        except (OSError, StopIteration):
            break
    targets = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) in excluded:
            continue
        try:
            if entry.stat().st_uid != os.getuid():
                continue
            argv = (entry / "cmdline").read_bytes().decode(errors="replace").split("\0")
            name = Path(argv[0]).name.lower()
            if name in ("bash", "zsh", "fish", "dash", "jj", "git") or "codex" in name or "pycharm" in name or (
                name == "java" and any("pycharm" in arg.lower() or "com.intellij.idea.Main" in arg for arg in argv)
            ):
                targets.append(int(entry.name))
        except OSError:
            continue
    return sorted(targets)


def strace_command(pids):
    syscalls = "open,openat,openat2,unlink,unlinkat,rename,renameat,renameat2,execve,execveat,clone,clone3,fork,vfork,exit,exit_group,kill,tgkill"
    return ["sudo", "-n", "strace", "-f", "-ttt", "-T", "-s", "4096", "-yy", "-e", "trace=" + syscalls] + [
        part for pid in pids for part in ("-p", str(pid))
    ]


def relevant_strace_line(line):
    # Preserve lifecycle/exec events and resumed calls: these provide context for short-lived creators.
    return "index.lock" in line or any(part in line for part in (
        "execve(", "execveat(", "clone(", "clone3(", "fork(", "vfork(", "exit(", "exit_group(",
        "kill(", "tgkill(", "resumed>", "+++", "---", "strace:"
    ))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--backend", choices=("strace", "bpftrace"), default="strace")
    parser.add_argument("--pid", type=int, action="append", help="strace target PID; repeat for multiple processes")
    args = parser.parse_args()
    if not shutil.which(args.backend):
        parser.error(f"{args.backend} is required")
    pids = args.pid or (trace_targets() if args.backend == "strace" else [])
    if args.backend == "strace" and not pids:
        parser.error("No IDE/Codex/shell targets found. Supply --pid PID for an existing process.")
    git_dir = subprocess.check_output(
        ["git", "-C", str(args.repository), "rev-parse", "--absolute-git-dir"], text=True
    ).strip()
    target = os.path.join(git_dir, "index.lock")
    output = args.output or Path(f"/tmp/jj-index-lock-{datetime.datetime.now():%Y%m%d-%H%M%S}.jsonl")
    os.umask(0o077)
    # Authenticate before starting the collector; no unattended privilege escalation.
    subprocess.run(["sudo", "-v"], check=True)
    with output.open("x", encoding="utf-8") as log:
        def write(record):
            record["observed_at"] = datetime.datetime.now().astimezone().isoformat()
            log.write(json.dumps(record, ensure_ascii=False) + "\n")
            log.flush()

        write({"event": "START", "target": target, "backend": args.backend,
               "lock_already_exists": Path(target).exists(), "targets": [process_info(pid) for pid in pids]})
        print(f"Starting observer for {target}\nPrivate log: {output}\nKeep it running while using the IDE; press Ctrl+C to stop.", flush=True)
        script = Path(__file__).with_name("jj-index-lock.bt")
        env = dict(os.environ, BPFTRACE_MAX_STRLEN="128")
        command = strace_command(pids) if args.backend == "strace" else [
            "sudo", "-n", "--preserve-env=BPFTRACE_MAX_STRLEN", "bpftrace", "-v", "-B", "line", str(script)
        ]
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env
        )
        try:
            recording = False
            for line in process.stdout:
                if args.backend == "strace":
                    if not relevant_strace_line(line):
                        continue
                    record = {"event": "STRACE", "line": line.rstrip()}
                    match = re.match(r"^(?:\[pid\s+(\d+)\]|\s*(\d+)\s+\d+\.\d+)", line)
                    if match:
                        record["process"] = process_info(int(match.group(1) or match.group(2)))
                    write(record)
                    if " attached" in line and not recording:
                        recording = True
                        print("strace attached: recording. Use the IDE normally.", flush=True)
                    if "index.lock" in line or "Operation not permitted" in line or "Permission denied" in line:
                        print(line.rstrip(), flush=True)
                    continue
                fields = line.rstrip("\n").split("\t")
                if len(fields) < 3 or not fields[2].isdigit():
                    write({"event": "COLLECTOR_MESSAGE", "message": line.rstrip()})
                    # Preserve verbose verifier diagnostics in the log without flooding the terminal.
                    if any(word in line for word in ("ERROR:", "WARNING:", "Attaching", "attached")):
                        print(line.rstrip(), flush=True)
                    continue
                event, kernel_ns, pid = fields[:3]
                pid = int(pid)
                record = {"event": event, "kernel_ns": kernel_ns, "fields": fields[3:],
                          "process": process_info(pid)}
                if event in ("OPEN", "UNLINK", "RENAME") and len(fields) >= 8:
                    path = resolve_path(pid, int(fields[5]), fields[7])
                    record["resolved_path"] = path
                    record["target_match"] = None if path is None else path == target
                    # Keep unresolved events: a short-lived creator may already have exited.
                    if path is not None and path != target:
                        continue
                    record["parent"] = process_info(int(fields[3]))
                    print(f"{event}: PID {pid} {fields[4]} {path or fields[7]}", flush=True)
                write(record)
        except KeyboardInterrupt:
            print("\nStopping collector.", flush=True)
        finally:
            if process.poll() is None:
                # SIGINT also reaches the tracer when Ctrl+C is pressed in this terminal.
                process.send_signal(2)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
            write({"event": "STOP", "collector_exit_code": process.returncode})
        if process.returncode not in (0, 130, -2):
            print("Collector failed. Full kernel/verifier diagnostics are saved in the private log above. This run does not exclude any lock creator.", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
