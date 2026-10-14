#!/usr/bin/env python3
"""Observe index.lock syscalls without modifying the repository or its locks (Linux)."""
import argparse
import datetime
import json
import os
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not shutil.which("bpftrace"):
        parser.error("bpftrace is required")
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

        write({"event": "START", "target": target, "lock_already_exists": Path(target).exists()})
        print(f"Starting observer for {target}\nPrivate log: {output}\nKeep it running while using the IDE; press Ctrl+C to stop.", flush=True)
        script = Path(__file__).with_name("jj-index-lock.bt")
        env = dict(os.environ, BPFTRACE_MAX_STRLEN="128")
        process = subprocess.Popen(
            ["sudo", "-n", "--preserve-env=BPFTRACE_MAX_STRLEN", "bpftrace", "-v", "-B", "line", str(script)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env
        )
        try:
            for line in process.stdout:
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
                # SIGINT also reaches bpftrace when Ctrl+C is pressed in this terminal.
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
