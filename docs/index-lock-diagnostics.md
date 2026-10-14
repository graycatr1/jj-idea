# Capturing a recurring Git index.lock failure

The local fork serializes jj commands per repository, including binary content reads.
After `Failed to reset Git HEAD state`, it pauses automatic commands for that repository
for the rest of the IDE session. A failed command may have updated Git HEAD without
committing the corresponding jj operation. A subsequent snapshotting query could import
that HEAD and create divergent changes. The operation log query remains available because
it explicitly uses `--ignore-working-copy`.

This is containment, not a fix for the unidentified lock creator. The plugin never deletes
`index.lock`, and never automatically retries the failed squash. External Git/jj processes
are not serialized by the plugin. After inspecting and recovering the repository in a
terminal, restart the IDE to resume normal commands. Merely deleting a lock does not prove
that Git HEAD and jj history are consistent.

## Start the observer before reproducing

On this Linux host, Python 3 and strace are already installed. From a separate terminal:

```bash
python3 /home/rl/PycharmProjects/jj-idea/scripts/capture-jj-index-lock.py /home/rl/PycharmProjects/paint-robot
```

The default backend is strace. It asks for your sudo password to attach to existing
processes, then selects user-owned PyCharm/Java, Codex, Git/jj and terminal shells.
The collector and its ancestors are excluded. The START record lists every selected PID,
command and working directory. strace follows their new children and attaches to the
selected processes' threads. Applications or terminals started later outside these process
trees are not covered. For a specific existing process, use `--pid PID` (repeatable).

Wait for `strace attached: recording` before reproducing. Keep the terminal open, use the
IDE, then press Ctrl+C after the error appears. The tracer detaches; it does not kill the
traced applications. It prints a private JSON-lines log under `/tmp`. The observer does
not modify repository content, remove locks, run squash, or run snapshotting jj queries.
strace can slow down the attached applications; use it for a short reproduction session.

STRACE records retain timestamped lock syscalls and their return values, exec arguments,
process creation/exit and signal events, plus available `/proc` metadata. `-yy` annotates
file descriptors and current-directory references. An open with `O_CREAT | O_EXCL` and a
non-negative result identifies a creator; `EEXIST` identifies an attempt blocked by an
existing lock. A successful rename from `index.lock` to `index`, or unlink, releases the
lock. Preserve unfinished/resumed lines and correlate by PID and timestamp. Short-lived
processes may exit before `/proc` metadata is available; syscall and exec records remain.
Other repositories' locks and the selected applications' exec arguments may appear in the
log. The log can contain command arguments and should only be shared for diagnosis.

The optional `--backend bpftrace` keeps the earlier system-wide BPF observer. It currently
fails to load probes on this host, so it is not the default. Verbose verifier diagnostics
are saved to the log; the terminal shows only brief startup/error messages. Its strings
are capped at 128 bytes; long paths can be truncated or missed.

If attachment fails, events are dropped, or the creator is outside the traced process
trees, the run cannot exclude that creator. A lock present before startup has no creation
event in this recording. Start the applications/terminals first, then the observer, before
reproducing; polling cannot recover the creator of a stale lock.

## Inspect an incident without triggering another import

Quit the IDE after stopping the observer, then save the following output:

```bash
git -C /home/rl/PycharmProjects/paint-robot rev-parse HEAD
git -C /home/rl/PycharmProjects/paint-robot reflog -8
jj -R /home/rl/PycharmProjects/paint-robot --ignore-working-copy op log -n 8
jj -R /home/rl/PycharmProjects/paint-robot --ignore-working-copy log -r '@ | @-' --no-graph
```

Do not blindly repeat the failed squash. Determine whether the lock owner is still running,
whether Git HEAD changed, and whether the matching jj operation completed. If duplicate
changes exist, compare their full commit IDs, parents and trees before choosing a recovery.
This document does not prescribe deletion or abandonment without that evidence.
