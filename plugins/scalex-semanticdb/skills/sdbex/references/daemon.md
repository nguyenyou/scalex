# Daemon Reference

## Usage

Start once per session — keeps the index hot in memory for <10ms queries:

```bash
bash "/path/to/sdbex-cli" daemon -w /project &
```

All subsequent CLI commands auto-detect the daemon and forward queries transparently. Output is identical whether the daemon is running or not. Falls back to local index (~1.5s) if the daemon isn't available.

## When to use

| Scenario | Approach |
|---|---|
| 1-2 queries | CLI directly |
| 3-5 related queries | `batch` |
| Many queries across a session | `daemon` |

## Self-termination

The daemon handles its own cleanup — no manual shutdown needed:

- **Idle timeout**: exits after 5 min of no requests (configurable: first positional arg in seconds)
- **Max lifetime**: exits after 30 min regardless of activity (configurable: second positional arg)
- **Auto-rebuild**: checks `.semanticdb` mtimes before each query (~7ms), rebuilds if stale

## Troubleshooting

- **Socket already exists**: a previous daemon is still running, or a stale socket file exists. The daemon auto-cleans stale sockets on startup.
- **Java 16+ required**: the Unix domain socket API requires Java 16+.
