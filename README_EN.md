# StarChat v1.2.0 — Velocity Cross-Server Chat Plugin

> 中文文档见 [README.md](README.md)。This is the English version of the documentation.

Whatever a player says is visible on every other backend server, and shows the
**Chinese display name of the source server**. Players can switch between the
**global / local** channels (global by default). Bedrock players connected to
Velocity via Geyser are supported with **no extra configuration**.

---

## 1. Where to Install (the easiest place to get wrong)

**This plugin goes into the `plugins/` directory of the Velocity proxy** (Vc-01 in
the architecture — the process that listens for players at the public entry point),
**not into the backend Paper/Purpur sub-servers.**

```
<Velocity install dir>/
├── velocity.jar
├── velocity.toml
├── forwarding.secret
└── plugins/
    └── StarChat-1.2.0.jar   ← put it here
```

The six (or more) backend Paper/Purpur sub-servers **do not install any component of
this plugin**. Cross-server chat is implemented at the proxy layer — Velocity is the
only place that can see players on all backend servers at once.

## 2. Dependencies

**No external dependencies.** The database drivers (sqlite-jdbc, mysql-connector-j)
are bundled into this jar via shadowJar — install and it works, no extra library
needed in the proxy.

- `velocity-api` is `compileOnly` and is **not** bundled (Velocity provides it at
  runtime);
- Bedrock compatibility, Adventure/MiniMessage, and YAML parsing (snakeyaml) are also
  provided by the Velocity runtime — not repackaged, not relocated;
- Not relocating the drivers is intentional: Velocity gives each plugin its own class
  loader (no cross-contamination), while relocating would break sqlite-jdbc's internal
  native-library path lookup.

## 3. Quick Start

1. Put `StarChat-1.2.0.jar` into the proxy's `plugins/` directory;
2. Start Velocity. On first start `plugins/StarChat/config.yml` is generated;
3. **Run `/chat servers` first** to see the server names the proxy actually recognizes,
   then fill in the mapping accordingly (see Section 5);
4. After editing config, run `/chat reload` to apply — no proxy restart needed
   (restarting would kick all online players).

That's it. The default config runs with zero changes: the database uses built-in
SQLite (`plugins/StarChat/chat_prefs.db`, auto-created tables), new players default to
the global channel, and unmapped servers show their raw name as the source.

## 4. Database (degrades gracefully, never slows the proxy)

- `database.type: sqlite` (default): works out of the box for a single Velocity
  instance; the file lives in the plugin data directory.
- `database.type: mysql`: host / **port** / database / account / password are all in
  the `database` section of `config.yml` — there are no hardcoded numbers in the code;
  changing database parameters requires a proxy restart to take effect. Table creation
  is automatic (under MySQL, the `uuid` primary key uses a short `VARCHAR`, because
  MySQL disallows `TEXT` primary keys).
- **Degradation**: when the database is unreachable / file corrupted / driver missing,
  the log prints `[星穹旅驿] 数据库不可用，已降级为内存存储` and the plugin keeps working
  fully (the in-memory cache is the normal path; the database only persists), except
  that channel preferences reset to default after a proxy restart. The database
  auto-reconnects when it recovers. All DB reads/writes run on a separate async thread
  and never block the main thread.

## 5. Server Display-Name Mapping (the focus of this version)

The `servers` section is an **open mapping table**: add or remove as many as you like;
the code contains no hardcoded server names. Multiple servers can map to the same
display name (one-to-many, unifying split-server sources).

```yaml
servers:
  fallback: SHOW_RAW        # unmapped servers: SHOW_RAW=show raw name (default) / HIDE=hide source / UNKNOWN=show fallback-text
  fallback-text: "未知"
  case-sensitive: false     # whether server-name matching is case-sensitive
  # ↓↓↓ add your servers here; the left key is the [servers] key from velocity.toml ↓↓↓
  # backend-a: "主城"
  # backend-b: "炽壤"
  # backend-b2: "炽壤"      # one-to-many: both split servers show "炽壤"
```

> ### ⚠️ The most important rule
> **The key must match the `[servers]` key in `velocity.toml` exactly** — that is
> Velocity's real internal server name, which often differs from the container
> instance name / display name (especially when the instance name contains spaces, the
> toml key is usually an abbreviation). **Don't guess from the instance name.** After
> installing, run:
>
> ```
> /chat servers
> ```
>
> It lists every server name the proxy actually recognizes, its display name, which
> ones are unmapped, and reverse-warns about mapping keys "written but not present in
> the proxy" (likely typos). Fill in from the output, then `/chat reload` to apply.

The startup log also prints the mapping count and details, and lists unmapped servers
as a reminder. Forgetting to map a new server on opening never errors: the default
`SHOW_RAW` shows the raw server name directly, guaranteeing messages always go out.
(`fallback` / `fallback-text` / `case-sensitive` are reserved keys and cannot be used
as mapping keys — rarely an issue.)

## 6. Message Format

`format.global` / `format.local` support MiniMessage tags; placeholders:
`{server}`, `{player}`, `{message}`. Player name and message content are **auto-escaped**,
so players cannot forge colors/tags with `<red>` or `&f`; `{server}` comes from config
and may carry the owner's own colors.

```yaml
format:
  global: "<gold>[全服]</gold> <aqua>[{server}]</aqua> <white>{player}</white>: <gray>{message}</gray>"
  local: "<gray>[本地] {player}: {message}</gray>"
```

The default uses only simple colors (gold/aqua/gray/white) and plain text — friendliest
to Bedrock (Geyser text conversion); don't use hover/click events (limited Bedrock
support). A malformed format won't crash the plugin: it degrades to plain text and warns.

## 7. Commands

| Command | Description | Permission |
|---|---|---|
| `/chat global` (`/chat g`) | Set to global channel | none |
| `/chat local` (`/chat l`) | Set to local channel | none |
| `/chat toggle` | Toggle between the two channels | none |
| `/chat status` | View current channel and storage mode | none |
| `/chat servers` | List servers recognized by the proxy and verify mappings | none |
| `/g <message>` | Say one line globally (does **not** change the default preference) | none |
| `/l <message>` | Say one line locally (does **not** change the default preference) | none |
| `/chat reload` | Hot-reload config (mappings, format, channel prompts, etc.) | `starchat.reload` |
| `/chat set <player> <global\|local>` | Admin force-set (online players only; preference saved by UUID) | `starchat.admin` |

Permissions use the standard Velocity permission system (assignable if a Velocity
permission plugin is installed); without permissions, switch/query commands are
available to everyone.

## 8. Channel Semantics

- The channel decides the **send scope**: GLOBAL → everyone via
  `proxyServer.getAllPlayers()`; LOCAL → only players on the same backend server
  (`ServerConnection.getServer().getPlayersConnected()`).
- Receivers are not limited by their own channel: global messages are visible to
  everyone (including those currently in LOCAL).
- Preference persistence: switching writes to the DB immediately; it survives reconnect,
  cross-server switching, and proxy restart (when sqlite/mysql is healthy).
- In-memory cache `Map<UUID, Channel>`: loaded async on join, removed on quit, zero DB
  lookups when speaking.

## 9. Ports & Security (red-line statement)

- This plugin runs **inside the Velocity process**: it **listens on no port, creates no
  HTTP server, and occupies none of the player-entry port or backend-forwarding ports**;
  port management stays entirely within Velocity's own configuration.
- Backend servers must continue to listen only on `127.0.0.1`; the `forwarding.secret`
  trust chain is untouched by this plugin. Cross-server chat only uses Velocity's native
  events and player-connection objects — no new network channel is added.

## 10. Client Compatibility (including the signed-chat-history pitfall)

- **Java clients 1.20.5+** (including 1.21.x): chat is no longer signed; the proxy
  cancels and redistributes safely.
- **Bedrock players via Geyser**: messages are unsigned and take the same path —
  covered natively.
- **Java clients 1.19.1 ~ 1.20.4**: chat messages are signed, and the Velocity protocol
  layer forbids the proxy from canceling them (canceling triggers a forced disconnect —
  a Mojang signature-mechanism limitation). For these clients this plugin automatically
  switches to a compatible path: it **does not cancel** the message; the backend server
  broadcasts its local-visible part as usual, and the plugin only delivers the global
  format to players on other servers. Functionality is not reduced (other servers still
  receive the message with the source Chinese name); only that player's in-server display
  uses the backend's native format. Old clients are never kicked — that is the top
  priority.

## 11. FAQ

- **Source display name not working?** Nine times out of ten the `config.yml` key
  doesn't match the `velocity.toml` `[servers]` key. Run `/chat servers` to verify, then
  `/chat reload`.
- **Adding a new backend server?** Register it in velocity.toml, then add one mapping
  line to the `servers` section; forgetting it won't error, handled by the `fallback`
  policy.
- **Switching to MySQL?** Create the database, edit the `database` section
  (type/host/port/database/username/password), and restart the proxy. Missing tables are
  auto-created. The connection string uses UTF-8 encoding and a short timeout to avoid
  thread stalls.
- **First-startup log** prints a self-check summary:
  `[星穹旅驿] 跨服聊天插件自检完成：正常 X 项 / 降级 Y 项`. Degraded items state their
  cause and impact; normally no action is needed.
- **Build:** `gradle build` (Java 21 + shadow 8.1.1), artifact
  `build/libs/StarChat-1.2.0.jar`.

## 12. Version Requirements

- Velocity **3.4.x** (compiled against velocity-api 3.4.0; `PlayerChatEvent` and related
  APIs are stable since 3.2+)
- Java 21 (Velocity 3.4 runtime itself requires Java 21)
- Independent of backend server version (proxy-layer forwarding; any 1.21.x backend works)
