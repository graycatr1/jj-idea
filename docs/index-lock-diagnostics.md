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

On this Linux host, Python 3 and bpftrace 0.25 are already installed. From any terminal:

```bash
python3 /home/rl/PycharmProjects/jj-idea/scripts/capture-jj-index-lock.py /home/rl/PycharmProjects/paint-robot
```

The script asks for your sudo password: bpftrace needs permission to attach kernel probes.
Keep this terminal open while using the IDE normally. After the error appears, leave the
collector running for a few more seconds, then press Ctrl+C. It prints the location of a
private JSON-lines log under `/tmp`. The script does not change repository content, remove
locks, run squash, or run snapshotting jj queries.

The log contains lock open attempts and their syscall results, unlink/rename attempts and
results, PID/parent PID, available command lines and working directories, process exit
status, and `kill` signals directed at tracked processes. A successful open with
`O_CREAT | O_EXCL` identifies a creator; `OPEN_RESULT` with `-17` (`EEXIST`) identifies a
process that encountered an existing lock. Successful rename from `index.lock` to `index`
or unlink is normal lock release. Match syscall entry and result by thread ID, not just PID.

The probes cover `open`/`openat`/`openat2`, `unlink`/`unlinkat`, and
`rename`/`renameat`/`renameat2` on Linux with the corresponding syscall tracepoints. Kernel timestamps are
monotonic nanoseconds; `observed_at` is the collector's wall-clock receipt time. Short-lived
processes may exit before `/proc` metadata can be read; such events are retained with an
unresolved path. Exec events retain jj/git arguments with bpftrace's length limits. Keep
unresolved events when interpreting results. The observer also records jj/git launches
outside the target repository to preserve parent/process context; the log may contain
command arguments and should be shared only as needed for diagnosis.

If tracing fails, or bpftrace reports dropped events, the run cannot exclude any creator.
A lock already present at startup has no creation event in this recording. Start before
reproducing; polling alone cannot recover the creator of an existing stale lock.

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
