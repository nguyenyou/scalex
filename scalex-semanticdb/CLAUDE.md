# scalex-semanticdb

## Release workflow

### Step 1: Release PR (merge first)
1. Move `[Unreleased]` section in `CHANGELOG.md` to the new version with date
2. Bump `SdbxVersion` in `src/model.scala`
3. Create PR, get it merged to main

### Step 2: Tag + release
4. Tag as `sdb-vX.Y.Z` and push — GitHub Actions (`release-semanticdb.yml`) builds the assembly JAR and creates a GitHub release with the `sdbx` binary + `.sha256` checksum

### Step 3: Plugin version bump
5. Bump `EXPECTED_VERSION` in `plugins/scalex-semanticdb/skills/sdbx/scripts/sdbx-cli`
6. Update `CHECKSUM_sdbx` in `sdbx-cli` — get hash from the `.sha256` release asset:
   ```bash
   gh release download sdb-vX.Y.Z -p "sdbx.sha256" -O -
   ```
7. Bump `version` for the `scalex-semanticdb` entry in `.claude-plugin/marketplace.json` (at repo root, NOT inside `plugins/`)
8. Commit, create PR, merge to main (main is protected — cannot push directly)

## Feature checklist

When adding or changing commands/flags in `src/cli.scala`:
- Update help text in `printUsage()`
- Update `plugins/scalex-semanticdb/skills/sdbx/SKILL.md` (commands, options, workflows, description frontmatter) and `plugins/scalex-semanticdb/skills/sdbx/references/commands.md`. **Always run `./scripts/check-skill-frontmatter.sh` after editing SKILL.md**
- Update `CHANGELOG.md` (in this directory)
- Update `README.md` (in this directory)
- Update `docs/ROADMAP.md`
- Update root `README.md` (scalex-semanticdb section)
