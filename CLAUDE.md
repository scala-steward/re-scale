# re-scale

Scala Native porting toolkit — enforcement gates, migration tracking, and conversion guides.

## Build

```bash
sbt compile          # compile
sbt install          # build native binary to .build/re-scale-bin
sbt test             # run tests
```

The `bin/re-scale` wrapper script locates `.build/re-scale-bin` at runtime.

## Release checklist

Every release must update the version in **all five** files and create a git tag:

1. `src/main/scala/rescale/Version.scala` — `Version.value`
2. `build.sbt` — `ThisBuild / version`
3. `.claude-plugin/marketplace.json` — `plugins[0].version`
4. `plugins/re-scale/.claude-plugin/plugin.json` — `version`
5. `plugins/re-scale/hooks/session-start.sh` — `RESCALE_VERSION`

After committing, tag and push:

```bash
git tag <VERSION>
git push origin master --tags
```

The session-start hook clones a specific tag into `~/.local/share/re-scale/<VERSION>/`, so the tag **must** exist on the remote before users pick up the new plugin version.
