# Preview changelog

Entries for features gated behind a preview access code (see
`docs/design/preview-gating-and-dnd-sequencing.md`). Nothing here is read by the build or the
release workflow — it exists so preview work still satisfies the CI changelog gate honestly,
without leaking to Marketplace change-notes before the feature is generally available.

Write entries in the same user-facing voice as `CHANGELOG.md` — no class names, method names, or
internal architecture terms — so they can be moved into `CHANGELOG.md`'s `[Unreleased]` section
verbatim at GA, condensed into a handful of user-facing bullets.

## Unreleased (preview)

- Operations tab: browse the repository's operation log (what jj did, when, and with which
  command) and revert any single operation. "Restore Repository to This Point" is available as a
  separate, confirmed action. Snapshot operations are hidden by default. Reach it from the "+" in the tab strip or VCS → Jujutsu. Off by default.
