package com.starinn.chat;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * StarChat v1.2.0 —— Velocity 跨服聊天互通插件。
 *
 * <p>部署位置：只装在 Velocity 代理进程（本网络即 Vc-01）的 plugins 目录，
 * 后端 Paper/Purpur 子服不安装任何组件。插件运行在 Velocity 进程内部：
 * 不监听任何端口、不创建 HTTP 服务、不占用玩家入口或后端转发端口。
 *
 * <p>工作方式：在 Velocity 层拦截 {@link PlayerChatEvent}，阻止消息按默认
 * 方式只发给当前后端服，然后按发送者的频道偏好广播：
 * 全服（GLOBAL）走 {@code proxyServer.getAllPlayers()} 触达所有后端服的玩家，
 * 单服（LOCAL）只发给同一后端服的在线玩家。来源服务器显示名全部来自
 * config.yml 的 servers 开放映射表，代码中不出现任何服务器名字面量。
 *
 * <p>基岩兼容：基岩玩家经 Geyser 接入 Velocity 后，其聊天同样触发
 * PlayerChatEvent，本插件天然覆盖，无需特殊处理；默认格式只用简单颜色与
 * 纯文本，避免基岩端不支持复杂标签。
 *
 * <p>容错：数据库不可用时自动降级为内存存储（功能完整、重启后偏好丢失）；
 * 任何模块失败只关闭对应功能，绝不让 Velocity 启动失败。
 */
@Plugin(
    id = "starchat",
    name = "StarChat",
    version = "1.2.0",
    description = "跨服聊天互通：全服/单服频道、来源服务器显示名完全可配置",
    authors = {"StarInn"}
)
public final class StarChat {

  /** 统一日志前缀。 */
  private static final String PREFIX = "[星穹旅驿] ";

  /**
   * 协议红线：1.19.1 ~ 1.20.4 的 Java 客户端聊天是“签名聊天”，Velocity 规定
   * 代理层取消签名消息会触发强制断开（协议限制，不是本插件能绕过的）。
   * 1.20.5 起 Mojang 移除了玩家聊天签名，取消消息完全安全；经 Geyser 接入的
   * 基岩玩家消息本来就不签名。因此对 1.20.5 以下的 Java 客户端，本插件采用
   * “不取消、仅向其它服务器补发”的兼容路径，保证任何客户端都不会被误踢。
   */
  private static final ProtocolVersion CHAT_CANCEL_SAFE_SINCE = ProtocolVersion.MINECRAFT_1_20_5;

  /** 消息格式占位符（一次遍历同时替换，避免玩家内容伪造占位符）。 */
  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(server|player|message)}");

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  /** 嵌套模块：服务器显示名映射 / 消息格式化 / 频道管理 / 数据库 / 配置。 */
  private final ServerNameMapper serverNameMapper = new ServerNameMapper();
  private final MessageFormatter messageFormatter = new MessageFormatter();
  private volatile Config config = Config.builtInDefaults();
  private volatile ChannelManager channelManager;
  private volatile Database database;

  @Inject
  public StarChat(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
    // Velocity 插件不使用 plugin.yml，监听器在这里通过事件总线注册。
    proxy.getEventManager().register(this, this);
  }

  // ------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    var version = proxy.getVersion();
    logger.info(PREFIX + "StarChat v1.2.0 启动，宿主：{} {}（Java {}）",
        version.getName(), version.getVersion(),
        System.getProperty("java.version", "?"));
    logger.info(PREFIX + "本插件运行在代理进程内部：不监听任何端口、不创建 HTTP 服务。");

    SelfCheck check = new SelfCheck();

    // 自检 1：数据目录可写
    Path configFile = dataDirectory.resolve("config.yml");
    if (prepareDataDirectory(configFile)) {
      check.pass("数据目录 " + dataDirectory);
    } else {
      check.degrade("数据目录", "目录不可写，本次运行使用内置默认配置");
    }

    // 自检 2：配置解析
    List<String> warnings = new ArrayList<>();
    Config loaded = Config.load(configFile, logger, warnings);
    this.config = loaded;
    if (warnings.isEmpty()) {
      check.pass("配置文件 config.yml");
    } else {
      warnings.forEach(w -> logger.warn(PREFIX + "配置提示：" + w));
      check.degrade("配置文件", warnings.size() + " 处按默认值处理");
    }

    // 自检 3：服务器显示名映射（开放表，数量不限；键必须与 velocity.toml [servers] 一致）
    serverNameMapper.apply(loaded);
    logger.info(PREFIX + "服务器显示名映射已加载：{} 条（大小写敏感：{}，未映射策略：{}）",
        serverNameMapper.size(), loaded.serverCaseSensitive, loaded.serverFallback);
    serverNameMapper.entries().forEach((raw, display) ->
        logger.info(PREFIX + "  映射：" + raw + " -> " + display));
    reportMappingDiff(check);

    // 自检 4：消息格式合法性
    messageFormatter.apply(loaded);
    List<String> formatProblems = messageFormatter.lint();
    if (formatProblems.isEmpty()) {
      check.pass("消息格式 format.global / format.local");
    } else {
      formatProblems.forEach(p -> logger.warn(PREFIX + "格式非法，该格式已回退内置默认：" + p));
      check.degrade("消息格式", "存在非法 MiniMessage 模板，已按默认格式运行");
    }

    // 自检 5：数据库（不可用则降级内存，绝不影响代理启动）
    Database db = Database.from(loaded, dataDirectory, logger);
    this.database = db;
    boolean dbOk;
    try {
      // 有界等待，超时按不可用处理，永远不卡住启动线程
      dbOk = db.initialize().get(20, TimeUnit.SECONDS);
    } catch (Exception ex) {
      dbOk = false;
    }
    if (dbOk) {
      check.pass("数据库（" + loaded.databaseType + "）");
    } else {
      logger.warn(PREFIX + "数据库不可用，已降级为内存存储");
      check.degrade("数据库", "已降级为内存存储：功能完整，代理重启后频道偏好丢失");
    }
    this.channelManager = new ChannelManager(
        proxy, db, () -> this.config.channelDefaultChannel());

    // 自检 6：命令注册（/chat、/g、/l；被其他插件占用只降级命令功能）
    if (registerCommands()) {
      check.pass("命令 /chat、/g、/l");
    } else {
      check.degrade("命令", "注册失败（可能被其他插件占用），聊天广播不受影响");
    }

    logger.info(PREFIX + "跨服聊天插件自检完成：正常 {} 项 / 降级 {} 项",
        check.ok, check.failed);
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    Database db = this.database;
    if (db != null) {
      db.shutdown();
    }
    ChannelManager manager = this.channelManager;
    if (manager != null) {
      manager.clear();
    }
    logger.info(PREFIX + "已关闭：数据库连接与频道缓存均已释放。");
  }

  // ------------------------------------------------------------------
  // 玩家进出：偏好加载与缓存清理（防内存泄漏）
  // ------------------------------------------------------------------

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    ChannelManager manager = this.channelManager;
    if (manager == null) {
      return;
    }
    // 异步读库，绝不阻塞网络线程；读完后进入内存缓存。
    manager.loadPreferencesAsync(event.getPlayer());
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    ChannelManager manager = this.channelManager;
    if (manager != null) {
      manager.onQuit(event.getPlayer().getUniqueId());
    }
  }

  // ------------------------------------------------------------------
  // 核心：聊天拦截与广播
  // ------------------------------------------------------------------

  /**
   * 拦截玩家聊天并按频道广播。
   *
   * <p>setResult 在 velocity-api 上带有废弃标记（针对 1.19.1~1.20.4 签名聊天），
   * 本方法只在 1.20.5+（聊天不再签名，取消安全）的路径上调用它；旧客户端走
   * “不取消、只向其它服务器补发”的路径，两类客户端都不会被踢。
   */
  @Subscribe
  @SuppressWarnings("deprecation")
  public void onPlayerChat(PlayerChatEvent event) {
    ChannelManager manager = this.channelManager;
    if (manager == null) {
      return; // 初始化尚未完成，保持原版行为放行
    }
    Player sender = event.getPlayer();
    String message = event.getMessage();
    if (message == null || message.isBlank()) {
      return; // 空消息不处理
    }
    ChannelManager.Channel channel = manager.get(sender.getUniqueId());

    if (sender.getProtocolVersion().compareTo(CHAT_CANCEL_SAFE_SINCE) >= 0) {
      // 现代客户端（含经 Geyser 的基岩玩家）：取消默认广播，由插件统一分发
      event.setResult(PlayerChatEvent.ChatResult.denied());
      broadcast(channel == ChannelManager.Channel.GLOBAL, sender, message, false);
    } else if (channel == ChannelManager.Channel.GLOBAL) {
      // 旧客户端签名聊天：让后端服照常广播本服，插件只补发给其它服务器的玩家
      broadcast(true, sender, message, true);
    }
    // 旧客户端 + 单服频道：后端服会自行在本服广播，插件不干预
  }

  /**
   * 分发一条聊天消息。
   *
   * @param global               true=全服格式与全服受众，false=本地格式与同服受众
   * @param sender               发送者
   * @param message              原始消息文本
   * @param excludeCurrentServer true=跳过发送者所在服的玩家（旧客户端兼容路径用）
   */
  private void broadcast(boolean global, Player sender, String message, boolean excludeCurrentServer) {
    ServerConnection connection = sender.getCurrentServer().orElse(null);
    String rawServerName = connection == null ? null : connection.getServerInfo().getName();
    String displayServer = serverNameMapper.getDisplayName(rawServerName);
    Component formatted = messageFormatter.chat(global, displayServer, sender.getUsername(), message);

    Collection<Player> recipients;
    if (!global) {
      // 单服频道：只发同一后端服的在线玩家
      recipients = connection == null
          ? List.of(sender)
          : connection.getServer().getPlayersConnected();
    } else if (excludeCurrentServer) {
      // 全服 + 排除本服（旧客户端兼容路径：本服由后端服照常广播）
      if (connection == null) {
        return; // 玩家正在换服，来源服务器无法确定，跳过补发
      }
      recipients = new ArrayList<>();
      for (Player other : proxy.getAllPlayers()) {
        String otherServer = other.getCurrentServer()
            .map(sc -> sc.getServerInfo().getName()).orElse(null);
        if (otherServer == null || !otherServer.equalsIgnoreCase(rawServerName)) {
          recipients.add(other);
        }
      }
    } else {
      // 全服：遍历代理上的所有在线玩家
      recipients = proxy.getAllPlayers();
    }
    for (Player recipient : recipients) {
      recipient.sendMessage(formatted);
    }
  }

  // ------------------------------------------------------------------
  // 命令
  // ------------------------------------------------------------------

  private boolean registerCommands() {
    var commandManager = proxy.getCommandManager();
    boolean allOk = true;
    allOk &= tryRegister(commandManager, "chat", new String[] {"starchat"}, new ChatCommand());
    allOk &= tryRegister(commandManager, "g", new String[0], new TempChannelCommand(true));
    allOk &= tryRegister(commandManager, "l", new String[0], new TempChannelCommand(false));
    return allOk;
  }

  /** 单个命令独立注册：某个别名被其他插件占用只影响它自己。 */
  private boolean tryRegister(
      com.velocitypowered.api.command.CommandManager commandManager,
      String alias, String[] aliases, SimpleCommand command) {
    try {
      commandManager.register(
          commandManager.metaBuilder(alias).aliases(aliases).plugin(this).build(), command);
      return true;
    } catch (RuntimeException ex) {
      logger.warn(PREFIX + "命令 /{} 注册失败：{}", alias, ex.getMessage());
      return false;
    }
  }

  /** /chat 主命令：global/local/toggle/status/servers/reload/set。 */
  private final class ChatCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();
      if (args.length == 0) {
        sendHelp(source);
        return;
      }
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "global", "g" -> switchFromSource(source, ChannelManager.Channel.GLOBAL);
        case "local", "l" -> switchFromSource(source, ChannelManager.Channel.LOCAL);
        case "toggle" -> {
          if (!(source instanceof Player player)) {
            sendMini(source, "<red>该命令需要以玩家身份执行。</red>");
            return;
          }
          ChannelManager manager = channelManager;
          if (manager == null) {
            return;
          }
          ChannelManager.Channel next =
              manager.get(player.getUniqueId()) == ChannelManager.Channel.GLOBAL
                  ? ChannelManager.Channel.LOCAL
                  : ChannelManager.Channel.GLOBAL;
          applyChannelSwitch(player, next);
        }
        case "status" -> showStatus(source);
        case "servers" -> sendServerList(source);
        case "reload" -> reloadConfig(source);
        case "set" -> forceSetChannel(source, args);
        default -> source.sendMessage(Component.text("未知子命令：" + args[0])
            .append(Component.newline())
            .append(Component.text("使用 /chat 查看帮助")));
      }
    }

    private void switchFromSource(CommandSource source, ChannelManager.Channel channel) {
      if (!(source instanceof Player player)) {
        sendMini(source, "<red>频道是针对玩家的设置，请在游戏内使用。</red>");
        return;
      }
      applyChannelSwitch(player, channel);
    }

    private void applyChannelSwitch(Player player, ChannelManager.Channel channel) {
      ChannelManager manager = channelManager;
      if (manager == null) {
        return;
      }
      manager.set(player.getUniqueId(), channel);
      Config cfg = config;
      sendMini(player, channel == ChannelManager.Channel.GLOBAL
          ? cfg.messageSwitchGlobal : cfg.messageSwitchLocal);
    }

    private void showStatus(CommandSource source) {
      if (!(source instanceof Player player)) {
        sendMini(source, "<red>控制台没有频道状态，请在游戏内使用。</red>");
        return;
      }
      ChannelManager manager = channelManager;
      if (manager == null) {
        return;
      }
      ChannelManager.Channel current = manager.get(player.getUniqueId());
      Database db = database;
      String storage = (db != null && db.isAvailable())
          ? "<gray>数据库持久化（重连/重启保持）</gray>"
          : "<yellow>内存（代理重启后恢复默认）</yellow>";
      sendMini(source, "<aqua>当前频道：</aqua>"
          + (current == ChannelManager.Channel.GLOBAL
              ? "<gold>全服 GLOBAL</gold>" : "<gray>单服 LOCAL</gray>")
          + "<dark_gray> ｜ 存储：" + storage + "</dark_gray>");
    }

    private void forceSetChannel(CommandSource source, String[] args) {
      if (!source.hasPermission("starchat.admin")) {
        sendMini(source, "<red>你没有权限（starchat.admin）。</red>");
        return;
      }
      if (args.length < 3) {
        sendMini(source, "<gray>用法：/chat set <玩家> <global|local></gray>");
        return;
      }
      Optional<ChannelManager.Channel> channel = ChannelManager.Channel.parseStrict(args[2]);
      if (channel.isEmpty()) {
        sendMini(source, "<red>频道只能是 global 或 local。</red>");
        return;
      }
      Player target = proxy.getPlayer(args[1]).orElse(null);
      if (target == null) {
        // 离线玩家无法取得 UUID，无法安全写入偏好表，明确要求在线
        source.sendMessage(Component.text("玩家 " + args[1] + " 不在线。频道按 UUID 存档，只支持在线玩家。"));
        return;
      }
      ChannelManager manager = channelManager;
      if (manager == null) {
        return;
      }
      manager.set(target.getUniqueId(), channel.get());
      applyChannelSwitch(target, channel.get());
      sendMini(source, "<green>已将 </green>"
          + "<white>" + target.getUsername() + "</white>"
          + "<green> 的频道设为：" + channel.get().displayName() + "</green>");
    }

    private void reloadConfig(CommandSource source) {
      if (!source.hasPermission("starchat.reload")) {
        sendMini(source, "<red>你没有权限（starchat.reload）。</red>");
        return;
      }
      List<String> warnings = new ArrayList<>();
      Config old = config;
      Config loaded = Config.load(dataDirectory.resolve("config.yml"), logger, warnings);
      StarChat.this.config = loaded;
      serverNameMapper.apply(loaded);
      messageFormatter.apply(loaded);
      warnings.forEach(w -> logger.warn(PREFIX + "配置提示：" + w));
      logger.info(PREFIX + "配置已热加载：服务器显示名映射 {} 条。", serverNameMapper.size());
      if (!loaded.databaseSectionEquals(old)) {
        sendMini(source, "<yellow>注意：database 段的改动需重启代理才生效（避免影响在线连接，本次未重连）。</yellow>");
      }
      sendMini(source, "<green>StarChat 配置已重新加载（无需重启代理）。映射 " + serverNameMapper.size()
          + " 条" + (warnings.isEmpty() ? "" : "，" + warnings.size() + " 条警告见后台日志") + "</green>");
    }

    private void sendServerList(CommandSource source) {
      Collection<RegisteredServer> servers = proxy.getAllServers();
      sendMini(source, "<aqua>Velocity 实际识别到的服务器：共 " + servers.size()
          + " 台；映射表 " + serverNameMapper.size() + " 条；未映射策略："
          + config.serverFallback + "</aqua>");
      if (servers.isEmpty()) {
        sendMini(source, "<dark_gray>（代理的 velocity.toml [servers] 段目前没有任何服务器）</dark_gray>");
      }
      for (RegisteredServer server : servers) {
        String raw = server.getServerInfo().getName();
        String display = serverNameMapper.getDisplayName(raw);
        boolean mapped = serverNameMapper.isMapped(raw);
        // 原始名一律走纯文本，避免名称里的特殊字符被当作格式标签
        Component line = Component.text(" - ").decoration(TextDecoration.ITALIC, false)
            .append(Component.text(raw))
            .append(Component.text(mapped ? "  →  " : "  （未配置映射，按 " + config.serverFallback + " 策略处理）  "));
        if (display != null) {
          line = line.append(Component.text(display));
        }
        source.sendMessage(line);
      }
      // 反向核对：映射表里写了但代理没有的键，多半是与 velocity.toml 不一致的笔误
      for (String key : serverNameMapper.entries().keySet()) {
        boolean present = servers.stream()
            .anyMatch(s -> serverNameMapper.matchesKey(key, s.getServerInfo().getName()));
        if (!present) {
          source.sendMessage(Component.text(" ⚠ 映射表中的 \"" + key
              + "\" 不在代理服务器列表内，请核对 velocity.toml 的 [servers] 段 key"));
        }
      }
      sendMini(source, "<dark_gray>提示：配置 key 必须与 velocity.toml 的 [servers] 段 key 完全一致（不是容器实例名）。</dark_gray>");
    }

    private void sendHelp(CommandSource source) {
      sendMini(source, "<aqua>StarChat 跨服聊天 </aqua><dark_gray>v1.2.0</dark_gray>");
      sendMini(source, "<gray> /chat global（/chat g）</gray><dark_gray> - 设为全服频道</dark_gray>");
      sendMini(source, "<gray> /chat local（/chat l）</gray><dark_gray> - 设为单服频道</dark_gray>");
      sendMini(source, "<gray> /chat toggle</gray><dark_gray> - 在全服/单服间切换</dark_gray>");
      sendMini(source, "<gray> /chat status</gray><dark_gray> - 查看当前频道</dark_gray>");
      sendMini(source, "<gray> /chat servers</gray><dark_gray> - 查看代理服务器与显示名映射（服主核对用）</dark_gray>");
      sendMini(source, "<gray> /g <消息></gray><dark_gray> - 临时全服说一句（不改偏好）</dark_gray>");
      sendMini(source, "<gray> /l <消息></gray><dark_gray> - 临时本地说一句（不改偏好）</dark_gray>");
      if (source.hasPermission("starchat.reload")) {
        sendMini(source, "<gray> /chat reload</gray><dark_gray> - 热加载配置</dark_gray>");
      }
      if (source.hasPermission("starchat.admin")) {
        sendMini(source, "<gray> /chat set <玩家> <global|local></gray><dark_gray> - 管理员强制设置</dark_gray>");
      }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      List<String> out = new ArrayList<>();
      String last = args.length == 0
          ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      if (args.length <= 1) {
        for (String sub : List.of("global", "local", "toggle", "status", "servers", "reload", "set")) {
          if (sub.startsWith(last)) {
            out.add(sub);
          }
        }
      } else if ("set".equalsIgnoreCase(args[0]) && args.length == 2) {
        proxy.matchPlayer(last).forEach(p -> out.add(p.getUsername()));
      } else if ("set".equalsIgnoreCase(args[0]) && args.length == 3) {
        for (String channel : List.of("global", "local")) {
          if (channel.startsWith(last)) {
            out.add(channel);
          }
        }
      }
      return out;
    }
  }

  /** /g 与 /l：临时用某频道说一句，不改动玩家默认偏好。 */
  private final class TempChannelCommand implements SimpleCommand {

    private final boolean global;

    private TempChannelCommand(boolean global) {
      this.global = global;
    }

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      if (!(source instanceof Player player)) {
        sendMini(source, "<red>请在游戏内使用 " + (global ? "/g" : "/l") + "。</red>");
        return;
      }
      String[] args = invocation.arguments();
      if (args.length == 0) {
        sendMini(source, "<gray>用法：" + (global ? "/g <消息>" : "/l <消息>")
            + "，只说一次，不改动你的默认频道。</gray>");
        return;
      }
      String text = String.join(" ", Arrays.copyOfRange(args, 0, args.length));
      // 临时发言经由代理分发，不经过后端聊天管线，任何客户端版本都不存在签名问题
      broadcast(global, player, text, false);
    }
  }

  /** 发送一条 MiniMessage 文本；标签写错时退化为纯文本，绝不抛异常。 */
  private void sendMini(CommandSource source, String mini) {
    try {
      source.sendMessage(miniMessage.deserialize(mini));
    } catch (RuntimeException ex) {
      source.sendMessage(Component.text(mini));
    }
  }

  // ------------------------------------------------------------------
  // 启动辅助
  // ------------------------------------------------------------------

  /** 确保数据目录存在；首次运行时释放默认 config.yml。 */
  private boolean prepareDataDirectory(Path configFile) {
    try {
      Files.createDirectories(dataDirectory);
      if (!Files.exists(configFile)) {
        try (InputStream in = StarChat.class.getResourceAsStream("/com/starinn/chat/default-config.yml")) {
          if (in != null) {
            Files.copy(in, configFile, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info(PREFIX + "首次运行，已释放默认配置：" + configFile);
          }
        }
      }
      return true;
    } catch (IOException ex) {
      logger.error(PREFIX + "数据目录初始化失败：" + ex.getMessage());
      return false;
    }
  }

  /** 比对映射表与 Velocity 实际服务器列表，未配置映射的在日志中列出提示。 */
  private void reportMappingDiff(SelfCheck check) {
    Collection<RegisteredServer> servers = proxy.getAllServers();
    List<String> unmapped = new ArrayList<>();
    for (RegisteredServer server : servers) {
      String raw = server.getServerInfo().getName();
      if (!serverNameMapper.isMapped(raw)) {
        unmapped.add(raw);
      }
    }
    if (unmapped.isEmpty()) {
      check.pass("服务器映射比对（全部已配置）");
    } else {
      logger.warn(PREFIX + "以下 {} 台服务器已在 velocity.toml 注册但未配置显示名映射（当前按 {} 处理，"
          + "补好映射后执行 /chat reload 即可生效）：{}",
          unmapped.size(), config.serverFallback, String.join("、", unmapped));
      check.pass("服务器映射比对（" + unmapped.size() + " 台未配置，按回退策略显示，不影响发送）");
    }
  }

  /** 自检计数。 */
  private final class SelfCheck {
    private int ok;
    private int failed;

    private void pass(String item) {
      ok++;
      logger.info(PREFIX + "√ " + item);
    }

    private void degrade(String item, String reason) {
      failed++;
      logger.warn(PREFIX + "× {}（{}）", item, reason);
    }
  }

  // ==================================================================
  // 嵌套类：Config
  // ==================================================================

  /**
   * 配置模型。servers 段是开放 Map：除三个保留键（fallback / fallback-text /
   * case-sensitive）外全部视为服务器映射，数量不限、自由增删；
   * 这里绝不预设任何具体服务器名。
   */
  static final class Config {

    // channel
    String channelDefault = "GLOBAL";

    // format
    String formatGlobal = "<gold>[全服]</gold> <aqua>[{server}]</aqua> <white>{player}</white>: <gray>{message}</gray>";
    String formatLocal = "<gray>[本地] {player}: {message}</gray>";

    // servers（开放映射表；值一律是配置出来的字符串）
    ServerNameMapper.Fallback serverFallbackEnum = ServerNameMapper.Fallback.SHOW_RAW;
    String serverFallback = "SHOW_RAW";
    String serverFallbackText = "未知";
    boolean serverCaseSensitive = false;
    Map<String, String> serverMappings = new LinkedHashMap<>();

    // database
    String databaseType = "sqlite";
    String databaseFile = "chat_prefs.db";
    String databaseHost = "127.0.0.1";
    int databasePort = 3306;
    String databaseName = "starinn";
    String databaseUser = "root";
    String databasePassword = "";

    // message
    String messageSwitchGlobal = "<green>已切换到全服频道</green>";
    String messageSwitchLocal = "<green>已切换到单服频道</green>";

    static Config builtInDefaults() {
      return new Config();
    }

    ChannelManager.Channel channelDefaultChannel() {
      return ChannelManager.Channel.parseOrDefault(channelDefault, ChannelManager.Channel.GLOBAL);
    }

    boolean databaseSectionEquals(Config other) {
      return databaseType.equals(other.databaseType)
          && databaseFile.equals(other.databaseFile)
          && databaseHost.equals(other.databaseHost)
          && databasePort == other.databasePort
          && databaseName.equals(other.databaseName)
          && databaseUser.equals(other.databaseUser)
          && databasePassword.equals(other.databasePassword);
    }

    /**
     * 从 YAML 文件加载。任何一处解析失败只影响该处（按默认值处理并记录警告），
     * 不会抛出异常、不会中断启动。
     */
    @SuppressWarnings("unchecked")
    static Config load(Path file, Logger logger, List<String> warnings) {
      Config cfg = builtInDefaults();
      if (file == null || !Files.isRegularFile(file)) {
        warnings.add("config.yml 不存在，本次使用内置默认配置");
        return cfg;
      }
      Object root;
      try (InputStream in = Files.newInputStream(file);
           InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        root = new Yaml().load(reader);
      } catch (Exception ex) {
        warnings.add("config.yml 解析失败（" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
            + "），本次使用内置默认配置");
        return cfg;
      }
      if (!(root instanceof Map<?, ?> map)) {
        warnings.add("config.yml 顶层不是键值结构，本次使用内置默认配置");
        return cfg;
      }

      // channel.default
      Map<String, Object> channel = section(map, "channel", warnings);
      cfg.channelDefault = string(channel, "default", cfg.channelDefault, warnings, "channel");
      String upper = cfg.channelDefault.trim().toUpperCase(Locale.ROOT);
      if (!upper.equals("GLOBAL") && !upper.equals("LOCAL")) {
        warnings.add("channel.default 只能是 GLOBAL 或 LOCAL，当前值 \"" + cfg.channelDefault + "\" 按 GLOBAL 处理");
        cfg.channelDefault = "GLOBAL";
      }

      // format
      Map<String, Object> format = section(map, "format", warnings);
      cfg.formatGlobal = string(format, "global", cfg.formatGlobal, warnings, "format");
      cfg.formatLocal = string(format, "local", cfg.formatLocal, warnings, "format");

      // servers：开放 Map，除保留键外全部是服务器映射
      Map<String, Object> servers = section(map, "servers", warnings);
      cfg.serverFallback = string(servers, "fallback", cfg.serverFallback, warnings, "servers");
      ServerNameMapper.Fallback fallback = ServerNameMapper.Fallback.parse(cfg.serverFallback);
      if (fallback == null) {
        warnings.add("servers.fallback 只能是 SHOW_RAW / HIDE / UNKNOWN，当前值 \"" + cfg.serverFallback
            + "\" 按 SHOW_RAW 处理");
        fallback = ServerNameMapper.Fallback.SHOW_RAW;
      }
      cfg.serverFallbackEnum = fallback;
      cfg.serverFallback = fallback.name();
      cfg.serverFallbackText = string(servers, "fallback-text", cfg.serverFallbackText, warnings, "servers");
      cfg.serverCaseSensitive = bool(servers, "case-sensitive", cfg.serverCaseSensitive, warnings, "servers");
      for (Map.Entry<?, ?> entry : servers.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (key.equals("fallback") || key.equals("fallback-text") || key.equals("case-sensitive")) {
          continue; // 保留配置键，不是服务器映射
        }
        Object value = entry.getValue();
        if (value == null) {
          warnings.add("servers." + key + " 没有配置显示名，忽略该映射");
          continue;
        }
        // 不预设任何键名：只要 servers 段里出现了其他键值对，就当作映射收下
        cfg.serverMappings.put(key, String.valueOf(value));
      }

      // database
      Map<String, Object> db = section(map, "database", warnings);
      cfg.databaseType = string(db, "type", cfg.databaseType, warnings, "database")
          .trim().toLowerCase(Locale.ROOT);
      if (!cfg.databaseType.equals("sqlite") && !cfg.databaseType.equals("mysql")) {
        warnings.add("database.type 只能是 sqlite 或 mysql，当前值 \"" + cfg.databaseType + "\" 按 sqlite 处理");
        cfg.databaseType = "sqlite";
      }
      cfg.databaseFile = string(db, "file", cfg.databaseFile, warnings, "database");
      cfg.databaseHost = string(db, "host", cfg.databaseHost, warnings, "database");
      Object port = db.get("port");
      int parsedPort = cfg.databasePort;
      if (port instanceof Number n) {
        parsedPort = n.intValue();
      } else if (port instanceof String s && !s.isBlank()) {
        try {
          parsedPort = Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
          warnings.add("database.port \"" + s + "\" 不是数字，按默认端口处理");
        }
      }
      if (parsedPort < 1 || parsedPort > 65535) {
        warnings.add("database.port " + parsedPort + " 超出合法范围（1~65535），按默认端口处理");
        parsedPort = 3306;
      }
      cfg.databasePort = parsedPort;
      cfg.databaseName = string(db, "database", cfg.databaseName, warnings, "database");
      cfg.databaseUser = string(db, "username", cfg.databaseUser, warnings, "database");
      cfg.databasePassword = string(db, "password", cfg.databasePassword, warnings, "database");

      // message
      Map<String, Object> message = section(map, "message", warnings);
      cfg.messageSwitchGlobal = string(message, "switch-global", cfg.messageSwitchGlobal, warnings, "message");
      cfg.messageSwitchLocal = string(message, "switch-local", cfg.messageSwitchLocal, warnings, "message");
      return cfg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<?, ?> root, String key, List<String> warnings) {
      Object value = root.get(key);
      if (value == null) {
        return Map.of();
      }
      if (value instanceof Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
      }
      warnings.add("配置段 " + key + " 不是键值结构，本段按默认值处理");
      return Map.of();
    }

    private static String string(Map<String, Object> section, String key, String def,
                                 List<String> warnings, String where) {
      Object value = section.get(key);
      if (value == null) {
        return def;
      }
      if (value instanceof String s) {
        return s;
      }
      if (value instanceof Number || value instanceof Boolean) {
        return String.valueOf(value); // 例如未加引号的密码数字
      }
      warnings.add(where + "." + key + " 的类型不合适（" + value + "），按默认值处理");
      return def;
    }

    private static boolean bool(Map<String, Object> section, String key, boolean def,
                                List<String> warnings, String where) {
      Object value = section.get(key);
      if (value == null) {
        return def;
      }
      if (value instanceof Boolean b) {
        return b;
      }
      String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
      if (s.equals("true")) {
        return true;
      }
      if (s.equals("false")) {
        return false;
      }
      warnings.add(where + "." + key + " 只能是 true/false，按默认值 " + def + " 处理");
      return def;
    }
  }

  // ==================================================================
  // 嵌套类：Database
  // ==================================================================

  /**
   * 频道偏好存储：sqlite（默认，文件放插件数据目录，零配置）或 mysql（参数全部
   * 来自配置，端口无硬编码）。所有读写都在独立线程池里异步执行，绝不阻塞
   * Velocity 网络线程；连接失败自动降级（available=false），此时上层继续
   * 使用内存缓存，功能完整可用。
   */
  static final class Database {

    private enum Kind { SQLITE, MYSQL }

    /**
     * 建表语句。偏好表结构：uuid + channel 两列。
     * sqlite 使用 TEXT 主键；MySQL 不允许 TEXT 作主键，用等价的短 VARCHAR。
     */
    private static final String DDL_SQLITE =
        "CREATE TABLE IF NOT EXISTS chat_prefs (uuid TEXT PRIMARY KEY, channel TEXT NOT NULL DEFAULT 'GLOBAL')";
    private static final String DDL_MYSQL =
        "CREATE TABLE IF NOT EXISTS chat_prefs (uuid VARCHAR(36) PRIMARY KEY, channel VARCHAR(16) NOT NULL DEFAULT 'GLOBAL')";
    private static final String UPSERT_SQLITE =
        "INSERT INTO chat_prefs(uuid, channel) VALUES(?, ?) ON CONFLICT(uuid) DO UPDATE SET channel = excluded.channel";
    private static final String UPSERT_MYSQL =
        "INSERT INTO chat_prefs(uuid, channel) VALUES(?, ?) ON DUPLICATE KEY UPDATE channel = VALUES(channel)";
    private static final String SELECT_CHANNEL =
        "SELECT channel FROM chat_prefs WHERE uuid = ?";
    /** 重连节流：两次尝试至少间隔 30 秒。 */
    private static final long RECONNECT_INTERVAL_MS = 30_000L;

    private final Kind kind;
    private final Path sqliteFile;
    private final String host;
    private final int port;
    private final String databaseName;
    private final String user;
    private final String password;
    private final Logger logger;
    private final ExecutorService io;
    private final AtomicLong lastReconnectAttempt = new AtomicLong();

    private volatile Connection connection;
    private volatile boolean available;

    private Database(Kind kind, Path sqliteFile, String host, int port, String databaseName,
                     String user, String password, Logger logger) {
      this.kind = kind;
      this.sqliteFile = sqliteFile;
      this.host = host;
      this.port = port;
      this.databaseName = databaseName;
      this.user = user;
      this.password = password;
      this.logger = logger;
      this.io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "StarChat-DB");
        thread.setDaemon(true);
        return thread;
      });
    }

    static Database from(Config cfg, Path dataDirectory, Logger logger) {
      Kind kind = "mysql".equals(cfg.databaseType) ? Kind.MYSQL : Kind.SQLITE;
      String fileName = cfg.databaseFile.isBlank() ? "chat_prefs.db" : cfg.databaseFile;
      return new Database(kind, dataDirectory.resolve(fileName), cfg.databaseHost,
          cfg.databasePort, cfg.databaseName, cfg.databaseUser, cfg.databasePassword, logger);
    }

    boolean isAvailable() {
      return available;
    }

    /** 建立连接并建表。返回是否成功；任何异常都在内部消化，不向外抛。 */
    CompletableFuture<Boolean> initialize() {
      return CompletableFuture.supplyAsync(this::openAndPrepare, io);
    }

    private boolean openAndPrepare() {
      try {
        Connection conn = openConnection();
        try (Statement statement = conn.createStatement()) {
          statement.execute(kind == Kind.SQLITE ? DDL_SQLITE : DDL_MYSQL);
        }
        this.connection = conn;
        this.available = true;
        logger.info(PREFIX + "数据库就绪：" + describe());
        return true;
      } catch (Throwable error) {
        this.available = false;
        logger.warn(PREFIX + "数据库初始化失败（{}）：{}", kind, error.getMessage());
        return false;
      }
    }

    private String describe() {
      return kind == Kind.SQLITE
          ? "sqlite 文件 " + sqliteFile.getFileName()
          : "mysql " + host + ":" + port + "/" + databaseName;
    }

    private Connection openConnection() throws SQLException {
      if (kind == Kind.SQLITE) {
        try {
          Files.createDirectories(sqliteFile.getParent());
        } catch (IOException ex) {
          throw new SQLException("无法创建 sqlite 数据目录：" + ex.getMessage(), ex);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
        try (Statement statement = conn.createStatement()) {
          statement.execute("PRAGMA busy_timeout=3000");
          try {
            statement.execute("PRAGMA journal_mode=WAL");
          } catch (SQLException ignored) {
            // 个别文件系统不支持 WAL，不影响使用
          }
        }
        return conn;
      }
      // MySQL：主机/端口/库名全部来自配置
      String url = "jdbc:mysql://" + host + ":" + port + "/" + databaseName
          + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
          + "&connectTimeout=5000&socketTimeout=15000";
      return DriverManager.getConnection(url, user, password);
    }

    /**
     * 读取玩家频道偏好。
     *
     * @return 已存库的原始频道字符串；未降级时为查询结果（可能为空表示首次），
     *         降级状态下恒为空（调用方使用默认频道）。
     */
    CompletableFuture<Optional<String>> loadChannel(UUID uuid) {
      if (!available) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return CompletableFuture.supplyAsync(() -> {
        try {
          return queryChannel(uuid);
        } catch (SQLException ex) {
          handleFailure("读取偏好", ex);
          return Optional.<String>empty();
        }
      }, io);
    }

    private Optional<String> queryChannel(UUID uuid) throws SQLException {
      Connection conn = requireConnection();
      try (PreparedStatement statement = conn.prepareStatement(SELECT_CHANNEL)) {
        statement.setString(1, uuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return Optional.empty(); // 新玩家：库里没有记录
          }
          String channel = resultSet.getString(1);
          return Optional.ofNullable(channel);
        }
      }
    }

    /** 保存玩家频道偏好（异步，不阻塞调用线程）。 */
    void saveChannel(UUID uuid, String channelName) {
      if (!available) {
        return; // 降级状态：内存缓存已生效，无需写库
      }
      io.execute(() -> {
        try {
          upsert(uuid, channelName);
        } catch (SQLException ex) {
          handleFailure("保存偏好", ex);
        }
      });
    }

    private void upsert(UUID uuid, String channelName) throws SQLException {
      Connection conn = requireConnection();
      try (PreparedStatement statement =
               conn.prepareStatement(kind == Kind.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL)) {
        statement.setString(1, uuid.toString());
        statement.setString(2, channelName);
        statement.executeUpdate();
      }
    }

    private Connection requireConnection() throws SQLException {
      Connection conn = this.connection;
      if (conn == null) {
        throw new SQLException("数据库尚未连接");
      }
      if (conn.isClosed()) {
        throw new SQLException("数据库连接已关闭");
      }
      return conn;
    }

    /** 运行期读写失败：记录日志，并按节流间隔尝试重连；重连失败则降级。 */
    private void handleFailure(String action, SQLException ex) {
      logger.warn(PREFIX + "数据库{}失败：{}", action, ex.getMessage());
      long now = System.currentTimeMillis();
      long previous = lastReconnectAttempt.get();
      if (now - previous < RECONNECT_INTERVAL_MS
          || !lastReconnectAttempt.compareAndSet(previous, now)) {
        return;
      }
      closeQuietly();
      if (openAndPrepare()) {
        logger.info(PREFIX + "数据库重连成功，持久化已恢复");
      } else {
        logger.warn(PREFIX + "数据库不可用，已降级为内存存储");
      }
    }

    private void closeQuietly() {
      Connection conn = this.connection;
      this.connection = null;
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
          // 关闭失败无所谓
        }
      }
    }

    /** 插件卸载/代理停机：释放连接与线程池。 */
    void shutdown() {
      this.available = false;
      closeQuietly();
      io.shutdown();
      try {
        if (!io.awaitTermination(3, TimeUnit.SECONDS)) {
          io.shutdownNow();
        }
      } catch (InterruptedException ex) {
        io.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  // ==================================================================
  // 嵌套类：ChannelManager
  // ==================================================================

  /**
   * 频道状态管理：内存缓存为主（每次发言零查库、零阻塞），数据库只做持久化。
   * 玩家 join 时异步加载偏好、quit 时移除缓存，防止内存泄漏。
   */
  static final class ChannelManager {

    /** 频道：全服（默认）或单服。 */
    enum Channel {
      GLOBAL,
      LOCAL;

      /** 严格解析：接受 global/g/local/l（不区分大小写）。 */
      static Optional<Channel> parseStrict(String raw) {
        if (raw == null) {
          return Optional.empty();
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
          case "GLOBAL", "G" -> Optional.of(GLOBAL);
          case "LOCAL", "L" -> Optional.of(LOCAL);
          default -> Optional.empty();
        };
      }

      /** 宽松解析：无法识别时回退默认值。 */
      static Channel parseOrDefault(String raw, Channel fallback) {
        return parseStrict(raw).orElse(fallback);
      }

      String displayName() {
        return this == GLOBAL ? "全服" : "单服";
      }
    }

    private final ProxyServer proxy;
    private final Database database;
    private final Supplier<Channel> defaultChannelSupplier;
    private final Map<UUID, Channel> cache = new ConcurrentHashMap<>();

    ChannelManager(ProxyServer proxy, Database database, Supplier<Channel> defaultChannelSupplier) {
      this.proxy = proxy;
      this.database = database;
      this.defaultChannelSupplier = defaultChannelSupplier;
    }

    /** 当前频道：内存缓存命中即用，未命中返回配置的默认频道（新玩家默认全服）。 */
    Channel get(UUID uuid) {
      return cache.getOrDefault(uuid, defaultChannelSupplier.get());
    }

    /** 异步加载玩家偏好（进服时调用）。 */
    void loadPreferencesAsync(Player player) {
      UUID uuid = player.getUniqueId();
      // 先放默认值避免加载完成前的空窗，随后按库内记录覆盖
      cache.putIfAbsent(uuid, defaultChannelSupplier.get());
      database.loadChannel(uuid).whenComplete((stored, error) -> {
        if (error != null || stored.isEmpty()) {
          return; // 新玩家或查询失败：维持默认
        }
        if (proxy.getPlayer(uuid).isEmpty()) {
          return; // 玩家已离开，不再回填，避免脏缓存
        }
        Channel.parseStrict(stored.get()).ifPresent(channel -> {
          if (channel != defaultChannelSupplier.get()) {
            // 覆盖 join 时放入的默认值；若玩家在此期间手动切换过则尊重手动值
            cache.computeIfPresent(uuid, (id, current) ->
                current == defaultChannelSupplier.get() ? channel : current);
          }
        });
      });
    }

    /** 设置频道并持久化。 */
    void set(UUID uuid, Channel channel) {
      cache.put(uuid, channel);
      database.saveChannel(uuid, channel.name());
    }

    /** 玩家离开：清缓存，防止内存泄漏（偏好已实时写库，重连时重新加载）。 */
    void onQuit(UUID uuid) {
      cache.remove(uuid);
    }

    void clear() {
      cache.clear();
    }
  }

  // ==================================================================
  // 嵌套类：ServerNameMapper
  // ==================================================================

  /**
   * 服务器显示名映射。逻辑集中于此：查表 → 命中返回显示名 → 未命中按回退
   * 策略处理 → 返回。所有需要显示服务器名的地方统一调用 {@link #getDisplayName}。
   * 映射内容全部来自配置，代码中不存在任何服务器名字面量。
   */
  static final class ServerNameMapper {

    /** 未配置映射时的回退策略。 */
    enum Fallback {
      /** 显示原始服务器名（默认，保证任何情况下消息都能正常发送）。 */
      SHOW_RAW,
      /** 不显示来源（格式化时整段 [服务器] 被移除）。 */
      HIDE,
      /** 显示配置的占位文字（fallback-text）。 */
      UNKNOWN;

      static Fallback parse(String raw) {
        if (raw == null) {
          return null;
        }
        try {
          return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          return null;
        }
      }
    }

    private volatile Map<String, String> normalizedKeys = Map.of();
    private volatile Map<String, String> rawEntries = Map.of();
    private volatile boolean caseSensitive = false;
    private volatile Fallback fallback = Fallback.SHOW_RAW;
    private volatile String fallbackText = "未知";

    void apply(Config cfg) {
      boolean sensitive = cfg.serverCaseSensitive;
      Map<String, String> normalized = new LinkedHashMap<>();
      cfg.serverMappings.forEach((raw, display) ->
          normalized.put(sensitive ? raw : raw.toLowerCase(Locale.ROOT), display));
      this.caseSensitive = sensitive;
      this.fallback = cfg.serverFallbackEnum;
      this.fallbackText = cfg.serverFallbackText;
      this.normalizedKeys = normalized;
      this.rawEntries = new LinkedHashMap<>(cfg.serverMappings);
    }

    /**
     * 取得服务器显示名。
     *
     * @param serverName Velocity 中的服务器名（来自 {@code ServerInfo.getName()}）
     * @return 显示名；返回 null 表示这条消息不显示来源（HIDE 策略或服务器未知）
     */
    String getDisplayName(String serverName) {
      if (serverName == null || serverName.isEmpty()) {
        return null;
      }
      String hit = normalizedKeys.get(key(serverName));
      if (hit != null) {
        return hit;
      }
      return switch (fallback) {
        case SHOW_RAW -> serverName;
        case HIDE -> null;
        case UNKNOWN -> fallbackText;
      };
    }

    /** 该服务器是否已在映射表中配置。 */
    boolean isMapped(String serverName) {
      return serverName != null && normalizedKeys.containsKey(key(serverName));
    }

    /** 映射表键与服务器名是否匹配（按大小写敏感设置比较）。 */
    boolean matchesKey(String mapKey, String serverName) {
      if (mapKey == null || serverName == null) {
        return false;
      }
      return caseSensitive
          ? mapKey.equals(serverName)
          : mapKey.equalsIgnoreCase(serverName);
    }

    Map<String, String> entries() {
      return rawEntries;
    }

    int size() {
      return normalizedKeys.size();
    }

    private String key(String serverName) {
      return caseSensitive ? serverName : serverName.toLowerCase(Locale.ROOT);
    }
  }

  // ==================================================================
  // 嵌套类：MessageFormatter
  // ==================================================================

  /**
   * 消息格式化：format.global / format.local 模板 + {server}/{player}/{message}
   * 占位符。玩家名与消息内容会被 MiniMessage 转义，玩家无法伪造颜色或标签；
   * 服务器显示名来自可信配置，允许包含颜色标签。
   */
  final class MessageFormatter {

    private volatile String globalTemplate = Config.builtInDefaults().formatGlobal;
    private volatile String localTemplate = Config.builtInDefaults().formatLocal;

    void apply(Config cfg) {
      // lint 通过才采用；非法模板保留当前值（初始为内置默认），保证消息永远发得出
      if (isValidTemplate(cfg.formatGlobal)) {
        this.globalTemplate = cfg.formatGlobal;
      }
      if (isValidTemplate(cfg.formatLocal)) {
        this.localTemplate = cfg.formatLocal;
      }
    }

    /** 构建一条聊天消息组件。 */
    Component chat(boolean global, String displayServer, String playerName, String rawMessage) {
      String template = global ? globalTemplate : localTemplate;
      Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
      StringBuilder builder = new StringBuilder(template.length() + rawMessage.length() + 32);
      while (matcher.find()) {
        String replacement = switch (matcher.group(1)) {
          case "server" -> displayServer == null ? "" : displayServer;
          case "player" -> miniMessage.escapeTags(playerName);
          default -> miniMessage.escapeTags(rawMessage);
        };
        matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
      }
      matcher.appendTail(builder);
      String out = builder.toString();
      if (displayServer == null && template.contains("{server}")) {
        // HIDE 策略：连同包裹的空方括号一起移除，避免留下孤零零的 "[]"
        out = out.replaceAll("\\[\\s*\\]", "");
      }
      try {
        return miniMessage.deserialize(out);
      } catch (RuntimeException ex) {
        // 兜底：万一某个组合解析失败，退回纯文本，消息本身永远发得出去
        return Component.text(displayServer == null
            ? "[" + playerName + "] " + rawMessage
            : "[" + displayServer + "] " + playerName + ": " + rawMessage);
      }
    }

    /** 返回不合法的模板列表（自检用）。 */
    List<String> lint() {
      List<String> problems = new ArrayList<>();
      if (!isValidTemplate(globalTemplate)) {
        problems.add("format.global");
      }
      if (!isValidTemplate(localTemplate)) {
        problems.add("format.local");
      }
      return problems;
    }

    private boolean isValidTemplate(String template) {
      try {
        // 只静态检查标签配对：占位符替换前模板里的 {..} 不是标签，不会误报
        miniMessage.deserialize(template);
        return true;
      } catch (IllegalArgumentException ex) {
        return false;
      }
    }
  }
}
