package dev.nimbuspowered.nimbus.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Plugin(
    id = "nimbus-bridge",
    name = "Nimbus Bridge",
    version = "0.0.0",  // overridden by build.gradle.kts from nimbusVersion
    description = "Hub commands & Cloud Bridge for Nimbus networks",
    authors = {"NimbusPowered"}
)
public class NimbusBridgePlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public NimbusBridgePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        var commandManager = server.getCommandManager();

        // Register /hub, /lobby, /l
        HubCommand hubCommand = new HubCommand(server);
        for (String alias : new String[]{"hub", "lobby", "l"}) {
            var meta = commandManager.metaBuilder(alias)
                .plugin(this)
                .build();
            commandManager.register(meta, hubCommand);
        }
        logger.info("Nimbus Bridge loaded — /hub, /lobby, /l registered");

        // Register /cloud bridge command (if bridge config exists)
        registerBridge(commandManager);

        // Register event listeners (maintenance handler may be null if no bridge config)
        server.getEventManager().register(this, new ConnectionListener(server, logger, () -> maintenanceHandler, this::findModdedServerForClient));

        // Unsubscribe players from event feed on disconnect
        server.getEventManager().register(this, new Object() {
            @Subscribe
            public void onDisconnect(DisconnectEvent event) {
                if (cloudCommand != null) {
                    cloudCommand.unsubscribePlayer(event.getPlayer().getUniqueId());
                }
            }
        });

        // Register permission provider (if bridge config exists)
        registerPermissionProvider();

        // Register proxy sync (tab list + MOTD)
        registerProxySync();

        // Register event stream on cloud command for /cloud events
        if (cloudCommand != null && sharedEventStream != null) {
            cloudCommand.registerEventStream(sharedEventStream);
        }

        // Connect shared event stream (after all handlers are registered)
        connectSharedEventStream();

        // Start health polling to track controller reachability
        startHealthPoller();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (proxySyncListener != null) {
            proxySyncListener.shutdown();
        }
        if (sharedEventStream != null) {
            sharedEventStream.close();
        }
        logger.info("Nimbus Bridge shut down");
    }

    private void registerProxySync() {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) return;

            ensureSharedClients(config);

            // Initialize maintenance handler
            maintenanceHandler = new MaintenanceHandler(logger);
            if (cloudCommand != null) {
                cloudCommand.setMaintenanceHandler(maintenanceHandler);
            }

            proxySyncListener = new ProxySyncListener(server, logger, sharedApiClient, sharedEventStream, sharedSdkClient);
            proxySyncListener.setMaintenanceHandler(maintenanceHandler);
            proxySyncListener.init();
            server.getEventManager().register(this, proxySyncListener);

            // Register maintenance event handlers
            registerMaintenanceEventHandlers();

            logger.info("Proxy Sync registered (tab list + MOTD + maintenance)");
        } catch (Exception e) {
            logger.warn("Failed to register proxy sync: {}", e.getMessage());
        }
    }

    private void registerMaintenanceEventHandlers() {
        sharedEventStream.onEvent("MAINTENANCE_ENABLED", e -> {
            maintenanceHandler.onMaintenanceEnabled(e.getData());
            // Refresh full state from API for MOTD/protocol/whitelist details
            maintenanceHandler.refreshFromApi(sharedApiClient);
        });
        sharedEventStream.onEvent("MAINTENANCE_DISABLED", e -> {
            maintenanceHandler.onMaintenanceDisabled(e.getData());
        });
    }

    private void connectSharedEventStream() {
        if (sharedEventStream != null) {
            // Register reconnect callback to re-sync state after controller comes back
            sharedEventStream.onReconnect(() -> {
                logger.info("Event stream reconnected — re-syncing proxy config and maintenance state");
                if (proxySyncListener != null) {
                    proxySyncListener.refetchConfig();
                }
                if (maintenanceHandler != null && sharedApiClient != null) {
                    maintenanceHandler.refreshFromApi(sharedApiClient);
                }
                refreshModdedGroups();
            });

            try {
                sharedEventStream.connect();
                logger.info("Shared event stream connected");
            } catch (Exception e) {
                logger.warn("Failed to connect shared event stream: {}", e.getMessage());
            }
        }
    }

    private void startHealthPoller() {
        if (sharedApiClient == null) return;
        server.getScheduler().buildTask(
            server.getPluginManager().getPlugin("nimbus-bridge").orElse(null),
            () -> {
                try {
                    var result = sharedApiClient.get("/api/health").join();
                    if (result.isSuccess()) {
                        if (!controllerReachable) {
                            logger.info("Controller is now reachable");
                            // Refresh remote commands on (re)connect
                            if (cloudCommand != null) cloudCommand.refreshRemoteCommands();
                            refreshModdedGroups();
                        }
                        controllerReachable = true;
                    } else {
                        if (controllerReachable) {
                            logger.warn("Controller became unreachable (HTTP {})", result.statusCode());
                        }
                        controllerReachable = false;
                    }
                } catch (Exception e) {
                    if (controllerReachable) {
                        logger.warn("Controller became unreachable: {}", e.getMessage());
                    }
                    controllerReachable = false;
                    logger.debug("Health check failed: {}", e.getMessage());
                }
            }
        ).repeat(10, java.util.concurrent.TimeUnit.SECONDS).schedule();
    }

    private NimbusPermissionProvider permissionProvider;
    private dev.nimbuspowered.nimbus.sdk.NimbusEventStream sharedEventStream;
    private dev.nimbuspowered.nimbus.sdk.NimbusClient sharedSdkClient;
    private NimbusApiClient sharedApiClient;
    private ProxySyncListener proxySyncListener;
    private MaintenanceHandler maintenanceHandler;
    private CloudCommand cloudCommand;
    private volatile boolean controllerReachable = false;

    /** Enriched modded group info cached from /api/groups. */
    private record ModdedGroupInfo(String name, String software, String version, int protocolVersion, Set<String> modIds) {}
    private final List<ModdedGroupInfo> moddedGroups = new CopyOnWriteArrayList<>();
    private static final Set<String> MODDED_SOFTWARE = Set.of("FORGE", "NEOFORGE", "FABRIC");

    /** Minecraft version → protocol version mapping. */
    private static final Map<String, Integer> MC_PROTOCOL = Map.ofEntries(
        Map.entry("1.7.10", 5), Map.entry("1.12.2", 340),
        Map.entry("1.16.5", 754), Map.entry("1.18.2", 758),
        Map.entry("1.19.2", 760), Map.entry("1.19.4", 762),
        Map.entry("1.20", 763), Map.entry("1.20.1", 763),
        Map.entry("1.20.2", 764), Map.entry("1.20.3", 765), Map.entry("1.20.4", 765),
        Map.entry("1.20.5", 766), Map.entry("1.20.6", 766),
        Map.entry("1.21", 767), Map.entry("1.21.1", 767),
        Map.entry("1.21.2", 768), Map.entry("1.21.3", 768),
        Map.entry("1.21.4", 769), Map.entry("1.21.5", 770)
    );

    private void ensureSharedClients(BridgeConfig config) {
        if (sharedSdkClient == null) {
            sharedSdkClient = new dev.nimbuspowered.nimbus.sdk.NimbusClient(config.getApiUrl(), config.getToken());
        }
        if (sharedApiClient == null) {
            sharedApiClient = new NimbusApiClient(config.getApiUrl(), config.getToken());
        }
        if (sharedEventStream == null) {
            sharedEventStream = sharedSdkClient.createEventStream();
        }
    }

    private void registerPermissionProvider() {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) return;

            ensureSharedClients(config);
            permissionProvider = new NimbusPermissionProvider(sharedApiClient, logger);

            // Register as Velocity's permission provider
            server.getEventManager().register(this, new PermissionListener(permissionProvider));

            // Listen for permission change events via shared WebSocket
            registerPermissionEventHandlers();

            logger.info("Nimbus Permission Provider registered");
        } catch (Exception e) {
            logger.warn("Failed to register permission provider: {}", e.getMessage());
        }
    }

    /**
     * Registers permission-related event handlers on the shared event stream.
     */
    private void registerPermissionEventHandlers() {
        sharedEventStream.onEvent("PERMISSION_GROUP_CREATED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PERMISSION_GROUP_UPDATED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PERMISSION_GROUP_DELETED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
            String uuid = e.get("uuid");
            if (uuid != null) {
                try {
                    refreshPlayerPermissions(java.util.UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                    refreshAllPermissions();
                }
            } else {
                refreshAllPermissions();
            }
        });
    }

    private void refreshAllPermissions() {
        if (permissionProvider == null) return;
        permissionProvider.invalidateAll();
        for (Player player : server.getAllPlayers()) {
            permissionProvider.loadPermissions(player.getUniqueId(), player.getUsername());
        }
        logger.debug("Refreshed permissions for {} online player(s)", server.getPlayerCount());
    }

    private void refreshPlayerPermissions(java.util.UUID uuid) {
        if (permissionProvider == null) return;
        server.getPlayer(uuid).ifPresent(player ->
            permissionProvider.loadPermissions(player.getUniqueId(), player.getUsername())
        );
    }

    private void registerBridge(com.velocitypowered.api.command.CommandManager commandManager) {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) {
                logger.info("No bridge.json found — /cloud commands disabled");
                return;
            }

            ensureSharedClients(config);
            cloudCommand = new CloudCommand(sharedApiClient, sharedSdkClient, server);

            // Discover available module commands from Controller
            cloudCommand.refreshRemoteCommands();

            // Register /cloud and /nimbus
            for (String alias : new String[]{"cloud", "nimbus"}) {
                var meta = commandManager.metaBuilder(alias)
                    .plugin(this)
                    .build();
                commandManager.register(meta, cloudCommand);
            }

            logger.info("Nimbus Bridge loaded — /cloud, /nimbus registered (API: {})", config.getApiUrl());
        } catch (Exception e) {
            logger.warn("Failed to load bridge config: {}", e.getMessage());
        }
    }

    /**
     * Fetches group list from the controller API and updates the modded groups cache.
     */
    private void refreshModdedGroups() {
        if (sharedApiClient == null) return;
        sharedApiClient.get("/api/groups").thenAccept(result -> {
            if (!result.isSuccess()) return;
            try {
                var wrapper = new com.google.gson.Gson().fromJson(result.body(), com.google.gson.JsonObject.class);
                var array = wrapper.getAsJsonArray("groups");
                if (array == null) return;
                var newGroups = new java.util.ArrayList<ModdedGroupInfo>();
                for (var element : array) {
                    var obj = element.getAsJsonObject();
                    String name = obj.get("name").getAsString();
                    String software = obj.has("software") ? obj.get("software").getAsString() : "";
                    if (!MODDED_SOFTWARE.contains(software.toUpperCase())) continue;

                    String version = obj.has("version") ? obj.get("version").getAsString() : "";
                    int protocol = MC_PROTOCOL.getOrDefault(version, -1);

                    Set<String> modIds = Set.of();
                    if (obj.has("modIds") && obj.get("modIds").isJsonArray()) {
                        modIds = obj.getAsJsonArray("modIds").asList().stream()
                            .map(e -> e.getAsString().toLowerCase())
                            .collect(Collectors.toSet());
                    }

                    newGroups.add(new ModdedGroupInfo(name, software.toUpperCase(), version, protocol, modIds));
                }
                moddedGroups.clear();
                moddedGroups.addAll(newGroups);
                if (!moddedGroups.isEmpty()) {
                    logger.info("Modded groups: {}", moddedGroups.stream()
                        .map(g -> g.name() + " (" + g.software() + " " + g.version() + ", " + g.modIds().size() + " mods)")
                        .collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                logger.debug("Failed to parse groups response: {}", e.getMessage());
            }
        });
    }

    /**
     * Finds the lobby server with the fewest players (least-players load balancing).
     */
    private static Optional<RegisteredServer> findLobby(ProxyServer server) {
        return findLobbyExcluding(server, null);
    }

    /**
     * Finds the lobby server with the fewest players, optionally excluding a specific server.
     */
    private static Optional<RegisteredServer> findLobbyExcluding(ProxyServer server, String excludeServerName) {
        return server.getAllServers().stream()
            .filter(s -> s.getServerInfo().getName().toLowerCase().startsWith("lobby"))
            .filter(s -> excludeServerName == null || !s.getServerInfo().getName().equalsIgnoreCase(excludeServerName))
            .min(java.util.Comparator.comparingInt(s -> s.getPlayersConnected().size()));
    }

    /**
     * Finds the best modded server for a client based on connection type, protocol version, and mod list overlap.
     */
    private Optional<RegisteredServer> findModdedServerForClient(String connType, int clientProtocol, Set<String> clientModIds) {
        if (moddedGroups.isEmpty()) return Optional.empty();

        // Filter by protocol version and connection type compatibility
        var candidates = moddedGroups.stream()
            .filter(g -> g.protocolVersion() == clientProtocol || g.protocolVersion() == -1)
            .filter(g -> isConnectionTypeCompatible(connType, g.software()))
            .toList();

        if (candidates.isEmpty()) return Optional.empty();

        // Score each group by mod list overlap
        String bestGroup = null;
        double bestScore = -1;

        for (var group : candidates) {
            if (group.modIds().isEmpty()) continue;
            long overlap = clientModIds.stream().filter(group.modIds()::contains).count();
            double score = overlap / (double) group.modIds().size();
            if (score > bestScore) {
                bestScore = score;
                bestGroup = group.name();
            }
        }

        // Best score >= 0.5 → route to that group
        if (bestGroup != null && bestScore >= 0.5) {
            return findLeastLoadedInGroup(bestGroup);
        }

        // Fallback: if exactly one group matches protocol+type, route there
        // (handles case where getModInfo() returns empty on older Forge versions)
        if (candidates.size() == 1) {
            return findLeastLoadedInGroup(candidates.get(0).name());
        }

        return Optional.empty();
    }

    /**
     * Checks if a client connection type is compatible with a server's modloader software.
     */
    private static boolean isConnectionTypeCompatible(String connType, String software) {
        return switch (connType) {
            case "LEGACY_FORGE" -> "FORGE".equals(software);
            case "MODERN_FORGE" -> "FORGE".equals(software) || "NEOFORGE".equals(software);
            default -> false;
        };
    }

    /**
     * Finds the least-loaded registered server belonging to a group.
     */
    private Optional<RegisteredServer> findLeastLoadedInGroup(String groupName) {
        return server.getAllServers().stream()
            .filter(s -> groupName.equals(extractGroupName(s.getServerInfo().getName())))
            .min(java.util.Comparator.comparingInt(s -> s.getPlayersConnected().size()));
    }

    /** Extracts group name from service name (e.g. "Modded-1" → "Modded"). */
    private static String extractGroupName(String serverName) {
        int lastDash = serverName.lastIndexOf('-');
        if (lastDash > 0) {
            String suffix = serverName.substring(lastDash + 1);
            try {
                Integer.parseInt(suffix);
                return serverName.substring(0, lastDash);
            } catch (NumberFormatException e) {
                return serverName;
            }
        }
        return serverName;
    }

    /**
     * Handles initial connection (force lobby) and kicked-from-server (fallback to lobby).
     * Also enforces global and group maintenance mode.
     */
    @FunctionalInterface
    private interface ModdedServerFinder {
        Optional<RegisteredServer> find(String connType, int protocol, Set<String> clientModIds);
    }

    private static class ConnectionListener {

        private final ProxyServer server;
        private final Logger logger;
        private final java.util.function.Supplier<MaintenanceHandler> maintenanceSupplier;
        private final ModdedServerFinder moddedServerFinder;
        private final net.kyori.adventure.text.minimessage.MiniMessage miniMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        private final boolean loadBalancerEnabled;
        private final int servicePort;

        ConnectionListener(ProxyServer server, Logger logger,
                           java.util.function.Supplier<MaintenanceHandler> maintenanceSupplier,
                           ModdedServerFinder moddedServerFinder) {
            this.server = server;
            this.logger = logger;
            this.maintenanceSupplier = maintenanceSupplier;
            this.moddedServerFinder = moddedServerFinder;
            this.loadBalancerEnabled = Boolean.getBoolean("nimbus.loadbalancer.enabled");
            this.servicePort = Integer.getInteger("nimbus.service.port", -1);
        }

        /**
         * Blocks direct connections that bypass the load balancer.
         * When the LB is active, the Minecraft handshake contains the port the client connected to.
         * If that port matches this proxy's service port, the player connected directly (not via LB).
         */
        @Subscribe
        public void onPreLogin(PreLoginEvent event) {
            if (!loadBalancerEnabled || servicePort <= 0) return;

            var virtualHost = event.getConnection().getVirtualHost();
            if (virtualHost.isPresent() && virtualHost.get().getPort() == servicePort) {
                var address = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
                logger.info("Blocked direct connection from {} on port {} (bypassing load balancer)", address, servicePort);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Please connect via the correct address.", NamedTextColor.RED)
                ));
            }
        }

        @Subscribe
        public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
            Player player = event.getPlayer();

            // Check global maintenance
            MaintenanceHandler mh = maintenanceSupplier.get();
            if (mh != null && mh.isGlobalEnabled()) {
                // Check bypass: whitelist by name or UUID
                boolean bypass = mh.isWhitelisted(player.getUsername())
                        || mh.isWhitelisted(player.getUniqueId().toString())
                        || player.hasPermission("nimbus.maintenance.bypass");
                if (!bypass) {
                    event.setInitialServer(null);
                    Component kickMsg = parseMiniMessage(mh.getKickMessage());
                    player.disconnect(kickMsg);
                    logger.info("Blocked {} from joining (global maintenance)", player.getUsername());
                    return;
                }
            }

            // Modded client detection: NeoForge/Forge clients cannot connect to Paper servers.
            // Detect via Velocity internal ConnectionType (set during handshake from FML marker).
            String connType = getConnectionType(player);
            if (!"VANILLA".equals(connType)) {
                int protocol = player.getProtocolVersion().getProtocol();
                Set<String> clientMods = getClientModIds(player);
                Optional<RegisteredServer> modded = moddedServerFinder.find(connType, protocol, clientMods);
                if (modded.isPresent()) {
                    event.setInitialServer(modded.get());
                    logger.info("Routed {} client {} (protocol {}, {} mods) to {}",
                        connType, player.getUsername(), protocol, clientMods.size(),
                        modded.get().getServerInfo().getName());
                    return;
                }
                // No matching modded server. Falling back to a Vanilla lobby would
                // immediately fail with a confusing "Outdated client" or FML-handshake
                // mismatch — kick with a clear message instead.
                logger.info("No matching modded group for {} client {} (protocol {}, {} mods) — rejecting connection",
                    connType, player.getUsername(), protocol, clientMods.size());
                event.setInitialServer(null);
                player.disconnect(buildModdedNoMatchKick(connType, protocol));
                return;
            }

            Optional<RegisteredServer> lobby = findLobby(server);
            if (lobby.isPresent()) {
                event.setInitialServer(lobby.get());
            } else {
                // No lobby available — kick with message
                event.setInitialServer(null);
                player.disconnect(
                    Component.text("No lobby server available. Please try again later.", NamedTextColor.RED)
                );
            }
        }

        /**
         * Builds the kick component shown to modded clients with no matching server.
         * Lists the modded groups available, so the player understands what version
         * + modloader they actually need to install.
         */
        private Component buildModdedNoMatchKick(String connType, int protocol) {
            String available;
            if (moddedGroups.isEmpty()) {
                available = "no modded servers are configured on this network.";
            } else {
                available = "available modded servers: " + moddedGroups.stream()
                    .map(g -> g.name() + " (" + g.software() + " MC " + g.version() + ")")
                    .collect(Collectors.joining(", "));
            }
            return Component.text()
                .append(Component.text("No compatible modded server for your client.\n", NamedTextColor.RED))
                .append(Component.text("Your client: " + connType + " (protocol " + protocol + ")\n", NamedTextColor.GRAY))
                .append(Component.text(available, NamedTextColor.GRAY))
                .build();
        }

        /**
         * Detects the connection type of a player by accessing Velocity's internal ConnectionType.
         * Returns "VANILLA", "LEGACY_FORGE", or "MODERN_FORGE".
         */
        private String getConnectionType(Player player) {
            try {
                // ConnectedPlayer.getConnection() → MinecraftConnection
                var getConnection = player.getClass().getMethod("getConnection");
                var connection = getConnection.invoke(player);
                // MinecraftConnection.getType() → ConnectionType
                var getType = connection.getClass().getMethod("getType");
                var type = getType.invoke(connection);
                String typeName = type.getClass().getSimpleName();
                // ConnectionTypes: VANILLA, LEGACY_FORGE, MODERN_FORGE
                if (typeName.contains("LegacyForge") || typeName.contains("LEGACY_FORGE")) {
                    return "LEGACY_FORGE";
                }
                if (typeName.contains("Forge") || typeName.contains("FORGE")
                        || !type.toString().equalsIgnoreCase("VANILLA")) {
                    return "MODERN_FORGE";
                }
                return "VANILLA";
            } catch (Exception e) {
                logger.debug("Could not detect connection type for {}: {}", player.getUsername(), e.getMessage());
                return "VANILLA";
            }
        }

        /**
         * Extracts the set of mod IDs from a player's mod info (provided by Forge handshake).
         */
        private Set<String> getClientModIds(Player player) {
            return player.getModInfo()
                .map(info -> info.getMods().stream()
                    .map(mod -> mod.getId().toLowerCase())
                    .collect(Collectors.toSet()))
                .orElse(Set.of());
        }

        @Subscribe
        public void onKickedFromServer(KickedFromServerEvent event) {
            Player player = event.getPlayer();
            String kickedFrom = event.getServer().getServerInfo().getName();

            // Don't redirect modded clients to a vanilla lobby — they'll just get kicked again.
            // Instead, try to find an alternate modded group at the same protocol version.
            String connType = getConnectionType(player);
            if (!"VANILLA".equals(connType) && !kickedFrom.toLowerCase().startsWith("lobby")) {
                int protocol = player.getProtocolVersion().getProtocol();
                Set<String> clientMods = getClientModIds(player);
                String kickedGroup = deriveGroupName(kickedFrom);

                // Try routing to a different modded group (exclude the one we were kicked from)
                Optional<RegisteredServer> alternate = server.getAllServers().stream()
                    .filter(s -> {
                        String group = deriveGroupName(s.getServerInfo().getName());
                        return !group.equals(kickedGroup) && !group.toLowerCase().startsWith("lobby");
                    })
                    .filter(s -> !s.getServerInfo().getName().equals(kickedFrom))
                    .min(java.util.Comparator.comparingInt(s -> s.getPlayersConnected().size()));

                // Only redirect if the alternate is actually from a modded group
                if (alternate.isPresent()) {
                    String altGroup = deriveGroupName(alternate.get().getServerInfo().getName());
                    // Re-use the modded server finder to verify this is a valid modded target
                    Optional<RegisteredServer> validAlt = moddedServerFinder.find(connType, protocol, clientMods);
                    if (validAlt.isPresent() && !deriveGroupName(validAlt.get().getServerInfo().getName()).equals(kickedGroup)) {
                        event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                            validAlt.get(),
                            Component.text("Redirected to alternate modded server.", NamedTextColor.YELLOW)
                        ));
                        logger.info("Redirected modded client {} to {} after kick from {}", player.getUsername(), validAlt.get().getServerInfo().getName(), kickedFrom);
                        return;
                    }
                }

                Component reason = event.getServerKickReason().orElse(
                    Component.text("Connection failed.", NamedTextColor.RED)
                );
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
                logger.info("Modded client {} disconnected after kick from {} (no alternate modded server)", player.getUsername(), kickedFrom);
                return;
            }

            if (kickedFrom.toLowerCase().startsWith("lobby")) {
                // Kicked from a lobby — try to find a different lobby
                Optional<RegisteredServer> otherLobby = findLobbyExcluding(server, kickedFrom);
                if (otherLobby.isPresent()) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        otherLobby.get(),
                        Component.text("Sent to another lobby.", NamedTextColor.YELLOW)
                    ));
                    logger.info("Redirected {} to {} after kick from {}", player.getUsername(), otherLobby.get().getServerInfo().getName(), kickedFrom);
                    return;
                }
            } else {
                // Kicked from a non-lobby server — send back to lobby (least players)
                Optional<RegisteredServer> lobby = findLobby(server);
                if (lobby.isPresent()) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        lobby.get(),
                        Component.text("Sent back to lobby.", NamedTextColor.YELLOW)
                    ));
                    logger.info("Redirected {} to {} after kick from {}", player.getUsername(), lobby.get().getServerInfo().getName(), kickedFrom);
                    return;
                }
            }

            // No lobby available — disconnect with message
            Component reason = event.getServerKickReason().orElse(
                Component.text("Connection lost.", NamedTextColor.RED)
            );
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
        }

        @Subscribe
        public void onServerPreConnect(com.velocitypowered.api.event.player.ServerPreConnectEvent event) {
            MaintenanceHandler mh = maintenanceSupplier.get();
            if (mh == null) return;

            Player player = event.getPlayer();
            var target = event.getOriginalServer();
            String serverName = target.getServerInfo().getName();
            String groupName = deriveGroupName(serverName);

            // Check group maintenance
            if (mh.isGroupInMaintenance(groupName)) {
                boolean bypass = mh.isWhitelisted(player.getUsername())
                        || mh.isWhitelisted(player.getUniqueId().toString())
                        || player.hasPermission("nimbus.maintenance.bypass");
                if (!bypass) {
                    event.setResult(com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult.denied());
                    Component msg = parseMiniMessage(mh.getGroupKickMessage(groupName));
                    player.sendMessage(msg);
                    logger.info("Blocked {} from joining {} (group {} in maintenance)", player.getUsername(), serverName, groupName);
                }
            }
        }

        private static String deriveGroupName(String serverName) {
            int lastDash = serverName.lastIndexOf('-');
            if (lastDash > 0) {
                String suffix = serverName.substring(lastDash + 1);
                try {
                    Integer.parseInt(suffix);
                    return serverName.substring(0, lastDash);
                } catch (NumberFormatException e) {
                    return serverName;
                }
            }
            return serverName;
        }

        private Component parseMiniMessage(String input) {
            return miniMessage.deserialize(dev.nimbuspowered.nimbus.sdk.ColorUtil.translate(input));
        }
    }

    /**
     * Injects the Nimbus permission provider into Velocity's permission system
     * and manages the permission cache lifecycle.
     */
    private static class PermissionListener {

        private final NimbusPermissionProvider provider;

        PermissionListener(NimbusPermissionProvider provider) {
            this.provider = provider;
        }

        @Subscribe
        public void onPermissionsSetup(PermissionsSetupEvent event) {
            if (event.getSubject() instanceof Player) {
                event.setProvider(provider);
            }
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            Player player = event.getPlayer();
            provider.loadPermissions(player.getUniqueId(), player.getUsername());
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            provider.invalidate(event.getPlayer().getUniqueId());
        }
    }

    private static class HubCommand implements SimpleCommand {

        private final ProxyServer server;

        HubCommand(ProxyServer server) {
            this.server = server;
        }

        @Override
        public void execute(Invocation invocation) {
            var source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                return;
            }

            // Already on a lobby?
            var currentServer = player.getCurrentServer().orElse(null);
            if (currentServer != null &&
                currentServer.getServerInfo().getName().toLowerCase().startsWith("lobby")) {
                player.sendMessage(Component.text("You are already on the lobby.", NamedTextColor.YELLOW));
                return;
            }

            Optional<RegisteredServer> lobbyServer = findLobby(server);

            if (lobbyServer.isEmpty()) {
                player.sendMessage(Component.text("No lobby server available.", NamedTextColor.RED));
                return;
            }

            String name = lobbyServer.get().getServerInfo().getName();
            player.sendMessage(
                Component.text("Connecting to ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text("...", NamedTextColor.GREEN))
            );
            player.createConnectionRequest(lobbyServer.get()).fireAndForget();
        }
    }

}
