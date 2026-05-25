# Nimbus

Lightweight, console-only Minecraft cloud system. Manages dynamic server instances (lobbies, game servers) from a single JAR.

## Install & Run

One-command installers for end users (Java 21 auto-installed, latest release from GitHub):

```bash
# Controller (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash

# Controller (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex

# Agent node (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.sh | bash

# Agent node (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.ps1 | iex
```

```bash
# Remote CLI (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.sh | bash

# Remote CLI (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.ps1 | iex
```

Install scripts: `install.sh`, `install.ps1`, `install-agent.sh`, `install-agent.ps1`, `install-cli.sh`, `install-cli.ps1` in repo root.

## Build from Source

```bash
./gradlew shadowJar                    # Fat JAR → nimbus-core/build/libs/nimbus-core-<version>-all.jar
./gradlew :nimbus-core:compileKotlin   # Quick compile check
java -jar nimbus-core/build/libs/nimbus-core-<version>-all.jar
```

`shadowJar` also builds and embeds module JARs (perms, display, scaling, players, punishments, resourcepacks) into `controller-modules/` inside the fat JAR.

Version is defined once in `gradle.properties` (`nimbusVersion=x.y.z`).

## Auto-Updates

`UpdateChecker` (`dev/nimbuspowered/nimbus/update/UpdateChecker.kt`) runs on startup:
- Queries `GET /repos/NimbusPowered/Nimbus/releases/latest` via GitHub API
- Compares semver (major.minor.patch) against `NimbusVersion.version` from JAR manifest
- Patch/minor: auto-downloads new JAR, swaps in place, keeps backup (`nimbus-backup.jar`)
- Major: shows changelog + `[y/N]` prompt via JLine before downloading
- Skipped when version = `dev` (source builds)
- Events: `NimbusUpdateAvailable`, `NimbusUpdateApplied` in `Events.kt`

## Release Workflow

`.github/workflows/release.yml` — manually triggered (`workflow_dispatch`):
- Input: version (optional, defaults to `gradle.properties`) + prerelease flag
- Builds `shadowJar`, uploads `nimbus-core-<version>.jar` + `nimbus-agent-<version>.jar`
- Creates a GitHub Release **draft** with auto-generated release notes

## Project Structure

Core (root):
- `nimbus-core` — Main application (entry point: `dev.nimbuspowered.nimbus.NimbusKt`)
- `nimbus-agent` — Remote agent node for multi-node clusters
- `nimbus-protocol` — Shared cluster message types
- `nimbus-cli` — Remote CLI: standalone JLine3 console that connects to controller via REST + WebSocket (no Minecraft dependencies)

Core Plugins (`plugins/`) — deployed on every service/proxy independent of installed modules:
- `plugins/sdk` — Server SDK (Spigot 1.8.8+ / Paper / Folia compatible, auto-deployed to backend servers)
- `plugins/bridge` — Velocity plugin: `/cloud` + `/nimbus` commands, cloud bridge, remote-command forwarding (incl. caller UUID for caller-scoped module commands) (Java, auto-embedded as resource `nimbus-bridge.jar` during build)

Module-owned plugins (live next to their owning module under `modules/<mod>/plugin[-variant]/`, auto-deployed only when that module is enabled via `ModuleContext.registerPluginDeployment(...)`):
- `modules/perms/plugin` — Permissions plugin (Spigot/Paper/Folia, builtin or LuckPerms provider)
- `modules/display/plugin` — Display plugin: server selector signs + NPCs via FancyNpcs (Spigot 1.13+ signs, Paper 1.20+ NPCs, Folia compatible)
- `modules/punishments/plugin-backend` — Chat mute listener (enforces mutes without registering commands)
- `modules/punishments/plugin-velocity` — Velocity listeners for login-enforcement (ban/ipban)
- `modules/resourcepacks/plugin` — Applies network-wide packs on player join, 1.20.3+ multi-pack stack, telemetry
- `modules/auth/plugin-velocity` — Delivers dashboard-initiated magic-link chat components via `AUTH_MAGIC_LINK_DELIVERY` event subscription (no command registration — `/nimbus dashboard …` runs through Bridge's remote-command forwarding)
- (no backup plugin — backup module runs entirely on the controller; no SDK calls into running services)

Controller Modules (`modules/`):
- `modules/api` — Module API: interfaces for external module developers (NimbusModule, ModuleContext, ModuleCommand, Migration)
- `modules/perms` — Permissions module: groups, tracks, prefix/suffix, audit log (extracted from core)
- `modules/display` — Display module: server selector signs + NPCs config (extracted from core)
- `modules/scaling` — Smart Scaling module: time-based schedules, predictive warmup, player count history
- `modules/players` — Player module: centralized tracking, session history, cross-server management (controller module, auto-deployed)
- `modules/punishments` — Punishments module: network-wide bans, tempbans, ipbans, mutes, kicks, warnings with auto-expiry loop (migration range 5000+)
- `modules/resourcepacks` — Resource pack registry: URL-referenced + locally hosted packs, GLOBAL/GROUP/SERVICE assignments, streaming upload with SHA-1 (migration range 6000+)
- `modules/backup` — Scheduled tar+zstd snapshots (services/dedicated/templates/config/state-sync/database) with GFS retention, single-pass SHA-256 manifest, multi-threaded zstd-jni compression, and live TOML config editing via REST (migration range 7000+)
- `modules/docker` — Opt-in Docker backend: services run as containers when `[group.docker] enabled = true` is set, otherwise as bare processes. Raw HTTP/1.1 over the Docker Engine socket (no `docker` CLI dep, JVM `UnixDomainSocketAddress`), TTY-mode attach for bidirectional stdin+stdout, cgroup-enforced memory/CPU limits, auto network + image pull, container crash recovery via label-based reattach. Controller-only in Phase 1 — agent-node Docker is a follow-up
- `modules/auth` — Dashboard auth + RBAC (v0.11): MC-account login via 6-digit code OR magic link (no passwords, no OAuth), TOTP 2FA with recovery codes, 7-day rolling sessions, `nimbus.dashboard.*` permission nodes resolved through the Perms module. API tokens keep working as implicit `nimbus.dashboard.admin` for full backwards compat. Auto-deploys `nimbus-auth-velocity.jar` to every Velocity proxy (registers `/dashboard login | login link | sessions | logout-all`, delivers magic-link chat components via `AUTH_MAGIC_LINK_DELIVERY` module event). Migration range 8000+ (dashboard_sessions, dashboard_login_challenges, dashboard_totp, dashboard_recovery_codes). TOTP secrets AES-GCM-encrypted with `config/modules/auth/session.key` (auto-generated, chmod 600). Session tokens stored as SHA-256 hash only — raw token lives in the dashboard httpOnly cookie/localStorage

Other:
- `dashboard` — Web Dashboard (BETA): Next.js + shadcn/ui management UI, connects to controller REST API + WebSocket. Live at `dashboard.nimbuspowered.org`. Separate app, not embedded in core JAR.
  - Beta-era conventions:
    - Every page wraps its body in `PageShell`, which owns the consistent header + loading/empty/error states. Do not reintroduce per-page skeletons or bare status branches.
    - `useApiResource` is the canonical data hook; no bare `useEffect` + `fetch` in pages. Mutations go through `apiFetch`, which auto-surfaces 4xx/5xx as toasts (opt-out with `{ silent: true }` for user-initiated flows that render their own errors).
    - Severity colors use CSS variables (`--severity-ok`, `--severity-warn`, `--severity-err`, `--severity-info`). Do not reintroduce hardcoded `emerald-*` / `amber-*` / `destructive` tailwind classes in status UI.
    - Polling intervals are standardized as `POLL.fast` (3s) / `POLL.normal` (5s) / `POLL.slow` (30s) and pause while the tab is hidden.
    - Dashboard version lives in `dashboard/package.json` (currently `0.11.0-beta.3`), is injected at build via `next.config.ts`, exposed through `dashboard/src/lib/version.ts`, and the sidebar renders a Beta/Alpha badge reflecting the channel. Dashboard version is independent of the controller patch cadence.

Gradle project names remain unchanged (`:nimbus-sdk`, `:nimbus-module-perms`, etc.) via `projectDir` mappings in `settings.gradle.kts`.

## Tech Stack

- Kotlin 2.1.10, Java 21, Gradle + Shadow plugin
- ktoml for TOML config parsing
- JLine 3 for interactive console
- kotlinx-coroutines for async (scaling loops, event bus, process I/O)
- Ktor Client (CIO) for downloading server JARs
- Ktor Server (CIO) for REST API + WebSocket, Ktor Server (Netty) for cluster WebSocket (TLS)
- Exposed (JetBrains ORM) + SQLite/MySQL/PostgreSQL for database, versioned migrations via MigrationManager, HikariCP connection pool for MySQL/PostgreSQL

## Architecture

```
nimbus-core/src/main/kotlin/dev/nimbuspowered/nimbus/
├── Nimbus.kt              # Entry point, bootstrap
├── api/                   # Ktor REST API + WebSocket (v0.2), ProxyEventRoutes (proxy event endpoint)
├── config/                # TOML config loading (NimbusConfig, GroupConfig)
├── console/               # JLine3 REPL, CommandDispatcher, 34 commands
├── database/              # Exposed ORM: DatabaseManager, MigrationManager, Tables, MetricsCollector, AuditCollector
├── event/                 # Coroutine-based EventBus + sealed Events
├── group/                 # ServerGroup runtime state, GroupManager
├── loadbalancer/          # TcpLoadBalancer, BackendHealthManager, strategies
├── module/                # ModuleManager, ModuleContextImpl (dynamic module loading)
├── scaling/               # ScalingEngine + ScalingRule (auto-scale by player count)
├── proxy/                 # ProxySyncManager (tab list, MOTD, chat, maintenance)
├── service/               # Service lifecycle, ProcessHandle, PortAllocator, ServerListPing, WarmPoolManager (warm pool)
├── setup/                 # First-run interactive SetupWizard
├── stress/                # StressTestManager (simulated player load testing)
├── template/              # TemplateManager, ConfigPatcher, SoftwareResolver (auto-download), ServiceDeployer (deploy-back), ModScanner (mod ID extraction)
├── cluster/               # ClusterServer (TLS WS), ClusterWebSocketHandler, NodeManager, NodeConnection, RemoteServiceHandle, RemoteFileProxy, TlsHelper (keystore + fingerprint), placement strategies
├── service/StateSyncManager  # Canonical state store for services with [group.sync] or [dedicated.sync] — atomic manifest-based push/pull with staging + hardlink optimization
├── update/                # UpdateChecker (GitHub Releases auto-updater)
└── velocity/              # VelocityConfigGen (auto-manage proxy server list, modded client config)
# Note: permissions, display code now lives in their respective module JARs

dashboard/src/              # Web Dashboard (Next.js, BETA)
├── app/                   # Next.js pages (login, dashboard, groups, services, etc.)
├── components/            # React components (shadcn/ui based)
└── lib/                   # API client, auth, utilities
```

## Configuration

- `config/nimbus.toml` — Main config (network, controller, console, paths, API, database, audit, cluster TLS)
- `config/groups/*.toml` — One file per server group (proxy, lobby, game servers)
- `config/dedicated/*.toml` — One file per dedicated service (single-instance, fixed-port, managed directory under `paths.dedicated`)
- `data/nimbus.db` — SQLite database (default, configurable to MySQL/PostgreSQL)
- `config/modules/display/*.toml` — Display configs per group (signs + NPCs)
- `config/modules/scaling/*.toml` — Smart Scaling configs per group (schedules + warmup)
- `config/modules/players/` — Player module config (tracking, session history)
- `config/modules/punishments/messages.toml` — Punishment kick/mute message templates (&-color codes, placeholders `{target}`, `{issuer}`, `{reason}`, `{remaining}`, `{expires}`)
- `data/resourcepacks/<uuid>.zip` — Locally hosted resource pack files (streamed in, SHA-1 computed during upload)
- `config/modules/backup/backup.toml` — Backup module config (schedules, retention, scope, excludes, compression); atomically rewritten by `PUT /api/backups/config` with hot-reload
- `config/modules/docker/docker.toml` — Docker module config (socket, defaults for memory/CPU limits, java image map, network). Per-service overrides live in the group/dedicated TOML under `[group.docker]` / `[dedicated.docker]` with `enabled = true` as the opt-in
- `data/backups/*.tar.zst` — Local backup archives with trailing `MANIFEST.sha256` entry (one line per file: `<hex-sha256>  <relative/path>`)
- `config/modules/syncproxy/motd.toml` — MOTD + maintenance mode config
- `config/modules/syncproxy/tablist.toml` — Tab list header, footer, player format
- `config/modules/syncproxy/chat.toml` — Chat format settings
- Environment variable overrides: `NIMBUS_API_TOKEN`, `NIMBUS_DB_*`, `NIMBUS_CLUSTER_TOKEN`, `NIMBUS_CLUSTER_KEYSTORE_PASSWORD` override TOML config values
- Config keys use `snake_case`, group/service names use `PascalCase` (validated: `[a-zA-Z0-9_-]` only)
- Scaling cooldowns: 30s after scale-up, 120s after scale-down (per group)
- Metrics retention: auto-pruned after 30 days
- Audit log retention: auto-pruned after 90 days (configurable via `[audit] retention_days`)
- MySQL connections use SSL by default (`useSSL=true`)
- `modules/` directory — Controller module JARs loaded at startup
- Module JARs embedded in core shadowJar under `controller-modules/` for SetupWizard extraction
- Group config fields: `scaling.warm_pool_size` (pre-staged services), `lifecycle.deploy_on_stop` (deploy-back on stop), `lifecycle.deploy_excludes` (files to skip during deploy-back), `group.templates` (list of template names, applied in order)

## Key Patterns

- Services named `<GroupName>-<N>` (e.g., `Lobby-1`, `BedWars-3`); dedicated services use the user-defined name as-is
- Dedicated services: single instance, fixed port, managed dir under `paths.dedicated/<name>/`, no template, optional proxy registration via `proxy_enabled` flag. Server JAR is auto-downloaded on first start via `softwareResolver.ensureJarAvailable()`. API at `/api/dedicated/*`, console command `dedicated`, dashboard page `/dedicated`
- Memory: `ServiceMemoryResolver` reads resident set size from `/proc/<pid>/status` (Linux/WSL) or `tasklist` (Windows) via `ProcessMemoryReader`; agent nodes push their services' RSS in cluster heartbeats. SDK `reportHealth()` only sends TPS now (memory is the controller's job). The displayed "max" includes a 30%/256MB JVM overhead budget on top of `-Xmx` so RSS comparison is fair
- Proxy forwarding mod sync: `ServiceFactory.syncProxyForwardingMods()` runs on every service prepare (groups + dedicated) and cleans up stale mods from the wrong modloader. `syncDedicatedProxyForwarding()` additionally patches the forwarding config (neoforwarding-server.toml / proxy-compatible-forge-server.toml / FabricProxy-Lite.toml). `ensureForwardingMod()` swaps between PCF and NeoForwarding automatically based on NeoForge MC version (1.20.2+ = NeoForwarding)
- Proxy ports: 25565+, backend ports: 30000+
- Velocity forwarding: `modern` if all backends >=1.13, else `legacy` (BungeeCord)
- Modded client routing: Bridge detects Forge/NeoForge clients via Velocity's internal `ConnectionType` (FML handshake marker), filters groups by protocol version + connection type, then scores by mod list overlap (`|clientMods ∩ serverMods| / |serverMods|`, threshold 0.5). ModScanner extracts mod IDs from template `mods/` JARs on group load
- VelocityConfigGen auto-sets `announce-forge = true` and increases timeouts when modded backends are present
- Via plugins (ViaVersion/ViaBackwards) only on backend servers, never on proxy
- Via plugin dependencies enforced: ViaBackwards auto-includes ViaVersion, ViaRewind requires ViaBackwards
- EULA auto-accepted for Paper/Purpur/Pufferfish/Leaf/Folia templates
- Pufferfish support: downloads from Jenkins CI (`ci.pufferfish.host`), treated as Paper-based (plugins, Via, performance optimizer)
- Leaf support: downloads from Leaf API (`api.leafmc.one`), PaperMC-compatible API, treated as Paper-based (plugins, Via, performance optimizer)
- Cardboard (BETA): optional Bukkit/Paper plugin support for Fabric servers, auto-downloads with iCommon dependency from Modrinth
- Folia: SDK + NimbusPerms are Folia-compatible via SchedulerCompat
- Performance optimizer: Aikar's JVM flags + Paper/Purpur/Pufferfish/Leaf/Folia config tuning (optimize=true default)
- Process ready detection: watches stdout for "Done" pattern (120s timeout, 180s for modded)
- Phased startup order: proxies first (waits for READY) → then backends; ScalingEngine starts after initial boot
- Graceful shutdown order: game servers → lobbies → proxies
- Shutdown requires confirmation: `shutdown` then `shutdown confirm` within 30s
- NimbusPerms auto-deployed to backend servers via module-registered `PluginDeployment`
- All Nimbus-managed plugins (SDK + every module's plugin) are deployed at runtime on **every service prepare** via `ServiceFactory.resolveModulePlugins()`. They never land in `templates/global/plugins/` — so templates stay user-owned only. Deleted plugins self-heal on next service start
- Bedrock support: Geyser + Floodgate auto-downloaded from GeyserMC API, key.pem centrally managed
- Permission system: groups, inheritance, tracks, meta, weight, audit log, debug — central DB on controller
- LuckPerms support: optional provider in NimbusPerms, syncs display data to controller for proxy features
- Database migrations: `MigrationManager` auto-applies versioned schema changes on startup; core uses V1 (baseline) + V2 (audit); modules register migrations via `ModuleContext.registerMigrations()`. Module-specific ranges: perms 1000+, scaling 2000+, players 3000+, display 4000+, punishments 5000+, resourcepacks 6000+, backup 7000+, auth 8000+
- Audit logging: `AuditCollector` subscribes to EventBus, batch-writes to `audit_log` table; `audit` console command + `GET /api/audit` endpoint
- CLI session tracking: `CliSessionTracker` records Remote CLI connections in `cli_sessions` table; `sessions` console command (active/history); `CliSessionConnected`/`CliSessionDisconnected` events displayed in local console
- Event actor tracking: `NimbusEvent.actor` field identifies trigger source (`system`, `console`, `api:admin`, `api:service`)
- Cluster TLS: Netty engine with native `sslConnector`; auto-generates self-signed keystore at `config/cluster.jks` if none configured; agents connect via `wss://` with configurable trust (`trusted_fingerprint`, `tls_verify`, `truststore_path`)
- Cluster bootstrap: `GET /api/cluster/bootstrap` (REST API port, gated by cluster token) returns fingerprint + PEM + wsUrl so the agent setup wizard can pin the cert on first connect. Console commands: `cluster cert` / `cluster cert regenerate` / `cluster bootstrap-url`
- Placement pinning: `[group.placement] node = "worker-1"` or `[dedicated.placement] node = "..."`; `fallback` = `wait` (default, refuse start) / `local` (UNSAFE for stateful) / `fail` (error)
- State sync: `[group.sync] enabled = true` (or `[dedicated.sync]`) makes a service float across nodes. Controller stores canonical in `services/state/<name>/` (groups) or `dedicated/<name>/` (dedicated). Agent pulls delta via manifest comparison on start, pushes delta on graceful stop. Atomic staging + per-service lock. Data-loss model: graceful stop = zero loss, unplanned crash = loss since last push. `extra_sans` / `public_host` in `[cluster]` for cert SAN and bootstrap URL overrides
- Service name stability: `ServiceFactory.prepare` reuses the lowest-numbered CRASHED/STOPPED slot instead of advancing to fresh numbers, so Lobby-1 stays Lobby-1 across crash-respawn cycles (important for sync canonical state keyed by name)
- Modules loaded from `modules/*.jar` via ServiceLoader + URLClassLoader
- Module lifecycle: init() → enable() → disable()
- Modules register commands, routes, plugin deployments, event formatters, and migrations via ModuleContext
- Modules can access late-registered services (e.g. ServiceManager) via `ModuleContext.registerService()`
- Embedded modules auto-discovered via build-generated `controller-modules/modules.list`
- SetupWizard lets users choose which modules to install
- `plugins` command: live search on Hangar + Modrinth with multi-select, version-aware, auto-installs dependencies
- Template stacking: `templates = ["base", "overlay"]` in group config, applied in order (later overrides earlier)
- Warm pool: `warm_pool_size` configures pre-staged services per group, PREPARED state between PREPARING and STARTING
- Service deployments: `deploy_on_stop = true` copies changed files back to template on service stop
- Player tracking: Bridge reports player events via `POST /api/proxy/events`, Player Module subscribes via EventBus
- Punishments: controller module holds canonical records + an in-memory cache of active bans/mutes keyed by UUID & IP. Bridge enforces on Velocity `PreLoginEvent` via `/api/punishments/check/{uuid}?ip=` with its own 5s TTL cache; backend plugin enforces mutes on chat. Superseding writes automatically revoke prior active punishments of the same class. Expiry loop deactivates tempbans/tempmutes every 30s (configurable). Permission nodes: `nimbus.punish.ban`, `nimbus.punish.mute`, ..., `nimbus.punish.bypass` (skip mute enforcement)
- Resource packs: controller module persists pack registry + assignments; backend plugin fetches `/api/resourcepacks/for-group/<group>` on player join with 10s local cache. Pack stack order is GLOBAL < GROUP < SERVICE, priority ascending within scope. Paper 1.20.3+ uses multi-pack UUID API via reflection; older versions fall back to the single highest-priority pack. Local pack files served unauthenticated at `/api/resourcepacks/files/{uuid}.zip` (SHA-1 hash in `setResourcePack` call prevents tampering)
- Backups: controller module archives services/dedicated/templates/config/state-sync/database into tar+zstd via Apache Commons Compress + zstd-jni with `setWorkers(N)` for native multi-threaded compression (3–5× faster than subprocess `tar --zstd`). Single-pass SHA-256 computed while streaming, written as trailing `MANIFEST.sha256` entry, re-checked by `backup verify`. SQLite DB dump uses `VACUUM INTO` (atomic); MySQL/Postgres shell out to `mysqldump`/`pg_dump` and skip with WARN + PARTIAL status if missing on PATH. Quiesce via existing `ServiceManager.executeCommand` (`save-off`/`save-all flush`/`save-on`). GFS retention per `(targetType, targetName, scheduleClass)`; `keep_manual = true` makes manual backups immune. Cron scheduler uses a hand-rolled 5-field POSIX evaluator. Config edits via `PUT /api/backups/config` validate + rewrite TOML atomically + hot-reload scheduler. Remote-node services are skipped with PARTIAL status (cluster streaming deferred). All routes ADMIN-only
- Web Dashboard (BETA): `dashboard.nimbuspowered.org` — browser-based UI connecting to controller API. Runs entirely client-side, API token stored in browser localStorage. CORS must include dashboard origin

## Cross-Version Compatibility

- Plugins (SDK, Perms, Display) support Spigot 1.8.8+ through latest Paper/Folia
- `dev.nimbuspowered.nimbus.sdk.compat` package provides cross-version abstractions:
  - `VersionHelper`: runtime detection of Folia, Adventure API, AsyncChatEvent
  - `SchedulerCompat`: Bukkit/Folia scheduler abstraction (delegates to `FoliaScheduler` on Folia)
  - `TextCompat`: Adventure/legacy text abstraction (delegates to `AdventureHelper` on Paper 1.16.5+)
- `api-version` removed from plugin.yml for universal loading
- Chat rendering: `ModernChatHandler` (Paper AsyncChatEvent) vs `LegacyChatHandler` (Bukkit AsyncPlayerChatEvent)
- Sign rendering: `TextCompat.setSignLine()` uses `sign.line(Component)` or `sign.setLine(String)` based on server
- Hologram text: `TextCompat.setCustomName()` for cross-version ArmorStand naming
- FancyNpcs features only available on Paper 1.20+ (soft dependency, graceful degradation)

## Code Style

- Kotlin, no frameworks (no Spring/DI). Direct object wiring in `Nimbus.kt`
- Coroutines for all async work (no raw threads)
- All console commands implement `execute(args, output: CommandOutput)` as the canonical path; `execute(args)` delegates via `ConsoleOutput()`
- Sealed classes for events (`Events.kt`) with generic `ModuleEvent` for module-fired events
- Enums for state (`ServiceState.kt`)
- ANSI-colored console output via `ConsoleFormatter`

## API (v0.2)

- Bearer token auth (`Authorization: Bearer <token>`), auto-generated if not configured
- REST: `/api/services`, `/api/services/health` (aggregated health summary), `/api/groups`, `/api/status`, `/api/players`, `/api/maintenance`, `/api/stress`, `/api/reload`, `/api/shutdown`, `/api/loadbalancer`, `/api/nodes`, `/api/metrics`, `/api/audit` (admin-only audit log), `/api/scaling/*` (smart scaling module), `/api/permissions/*` (perms module), `/api/displays/*` (display module), `/api/players/*` (player module), `/api/console/complete` (tab completion for Remote CLI), `/api/modpacks/*` (modpack import, CurseForge, server pack upload), `/api/plugins/*` (plugin search/install), `/api/software/*` (server software versions), `/api/cluster/bootstrap` (cert material for agent setup wizard, gated by cluster token), `/api/services/{name}/state/{manifest,file,sync}` (state sync pull/push for `[group.sync]` services, served on the TLS cluster port, gated by cluster token), `/api/backups/*` (backup module — admin-only: list/trigger/restore/verify/delete/download/prune + `GET|PUT /api/backups/config` for live TOML editing)
- Proxy events: `POST /api/proxy/events` — generic proxy event reporting (player connect/disconnect/switch)
- Player module: `/api/players/online`, `/api/players/history/{uuid}`, `/api/players/info/{uuid}`, `/api/players/stats`
- Punishments module: `GET /api/punishments`, `GET /api/punishments/{id}`, `GET /api/punishments/player/{uuid}` (history), `GET /api/punishments/check/{uuid}?ip=` (fast proxy login check, cached), `GET /api/punishments/mute/{uuid}`, `POST /api/punishments`, `DELETE /api/punishments/{id}` (revoke)
- Resource packs module: `GET /api/resourcepacks`, `POST /api/resourcepacks` (URL), `POST /api/resourcepacks/upload` (multipart stream, SHA-1 hashed), `DELETE /api/resourcepacks/{id}`, `POST /api/resourcepacks/{id}/assignments` (GLOBAL/GROUP/SERVICE), `GET /api/resourcepacks/for-group/{group}?service=` (plugin-facing stack), `POST /api/resourcepacks/status` (telemetry). **Public (no auth):** `GET /api/resourcepacks/files/{uuid}.zip` — MC clients fetch locally hosted packs here, protected by SHA-1 hash negotiation
- WebSocket: `/api/events` (live events), `/api/services/{name}/console` (bidirectional), `/api/console/stream` (Remote CLI: multiplexed command execution, events, screen sessions) — auth via `Authorization` header or `?token=` query param
- `/api/health` is always public (no auth), all other endpoints (including `/api/metrics`) require auth
- Rate limiting: 120 requests/minute global, 5 requests/minute for stress endpoints
- Error responses include machine-readable `error` codes (e.g. `SERVICE_NOT_FOUND`, `VALIDATION_FAILED`) — see `ApiErrors.kt`
- API token passed to child processes via `NIMBUS_API_TOKEN` environment variable (not visible in `ps`)

## Stress Testing

Simulates player load across backend servers without real Minecraft clients.

### Console Commands
```
stress start <players> [group] [--ramp <seconds>]   # Start stress test
stress stop                                          # Stop and clean up
stress ramp <players> [--duration <seconds>]         # Adjust target mid-test
stress status                                        # Show live status
```

### In-Game (Bridge)
```
/cloud stress start <players> [group] [rampSeconds]
/cloud stress stop
/cloud stress ramp <players> [durationSeconds]
/cloud stress status
```

### Behavior
- Only backend groups receive simulated players (proxy groups are excluded)
- Each service is capped at its `max_players` config value
- ScalingEngine is **paused** during active stress tests (won't scale up/down based on simulated players)
- Proxy services auto-reflect total backend player count
- Simulated player counts are reflected in proxy MOTD and tab header/footer
- Template download to agent nodes includes global plugins (SDK)

### REST API
```
GET  /api/stress              # Status (active, players, capacity, per-service)
POST /api/stress/start        # Body: {"players": 100, "group": "Lobby", "rampSeconds": 30}
POST /api/stress/stop         # Stop active test
POST /api/stress/ramp         # Body: {"players": 200, "durationSeconds": 60}
```

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
|------|----------|
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
