# Syncing with Upstream

This fork adds guardrails to [SaseQ/discord-mcp](https://github.com/SaseQ/discord-mcp). Follow these steps to pull upstream changes while preserving the safety layers.

## How to Sync

```bash
git fetch upstream
git checkout main
git merge upstream/main
```

Then merge `main` into your feature branches:

```bash
git checkout <feature-branch>
git merge main
```

## After Syncing

Run the full test suite:

```bash
~/.local/bin/dmvn test 2>&1 | tail -n 40
```

**Important**: `ToolClassificationTest` will fail if any new upstream `@Tool` is unclassified. For each new tool, decide its category and add it to `src/main/java/dev/saseq/guards/ToolClassification.java`:

- **`DESTRUCTIVE`**: Irreversible or removes access/data (deletions, bans, kicks, member disconnects, role removals, etc.)
- **`READ_ONLY`**: Queries that return data without mutation.
- **`WRITE_NON_DESTRUCTIVE`**: Creates, edits, or updates data (reversible).

## Never Push Upstream

Do NOT push to or open PRs against [SaseQ/discord-mcp](https://github.com/SaseQ/discord-mcp). Keep the guardrails fork separate.
