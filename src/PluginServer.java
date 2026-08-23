import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerLoginEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.extras.MojangAuth;
import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import net.minestom.server.ping.ResponseData;
import net.minestom.server.utils.time.TimeUnit;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.logging.*;

/**
 * Mintropy MC Server - 插件化Minecraft服务器
 * 基于Minestom，支持MC客户端连接和插件扩展
 * 
 * @version 2.0.0
 */
public class PluginServer {
    private static final Logger logger = Logger.getLogger("Mintropy");
    private static final String VERSION = "2.0.0";
    private static final String MC_VERSION = "1.20.4";
    private static final int PORT = 25565;
    
    private static final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private static final Map<String, CommandExecutor> commands = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    
    private static InstanceContainer instanceContainer;
    private static ServerAPI serverAPI;
    private static volatile boolean running = false;

    // ============ 插件接口 ============
    public interface Plugin {
        void onEnable();
        void onDisable();
        String getName();
        String getVersion();
    }

    // ============ 命令执行器接口 ============
    @FunctionalInterface
    public interface CommandExecutor {
        void onCommand(Player player, String[] args);
    }

    // ============ 服务器API接口 ============
    public interface ServerAPI {
        void registerCommand(String command, CommandExecutor executor);
        void broadcastMessage(String message);
        void scheduleTask(Runnable task, long delay);
        Logger getLogger();
        Collection<Player> getOnlinePlayers();
        void teleportPlayer(Player player, double x, double y, double z);
        void setBlock(int x, int y, int z, String blockType);
        InstanceContainer getMainInstance();
    }

    // ============ 服务器API实现 ============
    private static class ServerAPIImpl implements ServerAPI {
        @Override
        public void registerCommand(String command, CommandExecutor executor) {
            commands.put(command.toLowerCase(), executor);
            logger.info("[API] 注册命令: /" + command);
        }

        @Override
        public void broadcastMessage(String message) {
            MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> {
                player.sendMessage(message);
            });
            logger.info("[广播] " + message);
        }

        @Override
        public void scheduleTask(Runnable task, long delay) {
            scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public Logger getLogger() {
            return logger;
        }

        @Override
        public Collection<Player> getOnlinePlayers() {
            return MinecraftServer.getConnectionManager().getOnlinePlayers();
        }

        @Override
        public void teleportPlayer(Player player, double x, double y, double z) {
            player.teleport(new Pos(x, y, z));
        }

        @Override
        public void setBlock(int x, int y, int z, String blockType) {
            Block block = Block.fromNamespaceId(blockType);
            if (block != null) {
                instanceContainer.setBlock(x, y, z, block);
            }
        }

        @Override
        public InstanceContainer getMainInstance() {
            return instanceContainer;
        }
    }

    // ============ 插件类加载器 ============
    private static class PluginClassLoader extends URLClassLoader {
        public PluginClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }
    }

    // ============ 插件配置类 ============
    private static class PluginConfig {
        String name;
        String version;
        String mainClass;
        String description;
        String author;

        PluginConfig(String name, String version, String mainClass) {
            this.name = name;
            this.version = version;
            this.mainClass = mainClass;
        }
    }

    // ============ 主方法 ============
    public static void main(String[] args) {
        printBanner();
        
        // 初始化Minecraft服务器
        MinecraftServer minecraftServer = MinecraftServer.init();
        
        // 创建世界实例
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        instanceContainer = instanceManager.createInstanceContainer();
        
        // 设置世界生成器（平地世界）
        instanceContainer.setGenerator(unit -> {
            unit.modifier().fillHeight(0, 1, Block.GRASS_BLOCK);
            unit.modifier().fillHeight(1, 40, Block.STONE);
        });
        
        // 初始化服务器API
        serverAPI = new ServerAPIImpl();
        
        // 注册事件
        registerEvents();
        
        // 加载插件
        loadPlugins("plugins");
        enableAllPlugins();
        
        // 启动服务器
        minecraftServer.start("0.0.0.0", PORT);
        running = true;
        
        logger.info("Mintropy MC 服务器启动完成！");
        logger.info("MC版本: " + MC_VERSION);
        logger.info("端口: " + PORT);
        logger.info("输入 'help' 查看可用命令");
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                stop();
            }
        }));
        
        // 主线程处理控制台输入
        try (Scanner scanner = new Scanner(System.in)) {
            while (running && scanner.hasNextLine()) {
                System.out.print("> ");
                System.out.flush();
                String input = scanner.nextLine().trim();
                if (!input.isEmpty()) {
                    handleConsoleCommand(input);
                }
            }
        } catch (Exception e) {
            logger.severe("控制台输入错误: " + e.getMessage());
        }
    }

    // ============ 注册MC事件 ============
    private static void registerEvents() {
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        
        // 服务器列表Ping事件
        globalEventHandler.addListener(ServerListPingEvent.class, event -> {
            ResponseData responseData = event.getResponseData();
            responseData.setDescription("§a§lMintropy Server §r§7- 插件化MC服务器");
            responseData.setMaxPlayer(100);
            responseData.setOnline(MinecraftServer.getConnectionManager().getOnlinePlayers().size());
            responseData.setVersion("1.20.4");
        });
        
        // 玩家配置事件
        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instanceContainer);
            Player player = event.getPlayer();
            player.setRespawnPoint(new Pos(0, 42, 0));
        });
        
        // 玩家登录事件
        globalEventHandler.addListener(PlayerLoginEvent.class, event -> {
            Player player = event.getPlayer();
            logger.info("玩家登录: " + player.getUsername());
            broadcastToConsole("§e" + player.getUsername() + " §a加入了服务器");
        });
        
        // 玩家生成事件
        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            player.setGameMode(GameMode.CREATIVE);
            player.teleport(new Pos(0, 42, 0));
            player.sendMessage("§a§l欢迎来到 §e§lMintropy §a§l服务器！");
            player.sendMessage("§7使用 §e/help §7查看可用命令");
        });
        
        // 玩家聊天事件
        globalEventHandler.addListener(PlayerChatEvent.class, event -> {
            Player player = event.getPlayer();
            String message = event.getMessage();
            
            // 检查是否是命令
            if (message.startsWith("/")) {
                event.setCancelled(true);
                handlePlayerCommand(player, message.substring(1));
                return;
            }
            
            // 广播聊天消息
            String formattedMessage = "§7<§f" + player.getUsername() + "§7> §f" + message;
            MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(p -> {
                p.sendMessage(formattedMessage);
            });
            logger.info("[聊天] " + player.getUsername() + ": " + message);
        });
        
        // 玩家断开连接
        globalEventHandler.addListener(PlayerDisconnectEvent.class, event -> {
            Player player = event.getPlayer();
            logger.info("玩家断开: " + player.getUsername());
            broadcastToConsole("§e" + player.getUsername() + " §c离开了服务器");
        });
    }

    // ============ 广播到控制台 ============
    private static void broadcastToConsole(String message) {
        logger.info(message.replaceAll("§[0-9a-fk-or]", ""));
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(p -> {
            p.sendMessage(message);
        });
    }

    // ============ 处理玩家命令 ============
    private static void handlePlayerCommand(Player player, String commandLine) {
        String[] parts = commandLine.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        // 检查插件命令
        CommandExecutor executor = commands.get(command);
        if (executor != null) {
            try {
                executor.onCommand(player, args);
            } catch (Exception e) {
                player.sendMessage("§c命令执行错误: " + e.getMessage());
                logger.severe("命令执行失败: " + command + " - " + e.getMessage());
            }
            return;
        }
        
        // 内置命令
        switch (command) {
            case "spawn":
                player.teleport(new Pos(0, 42, 0));
                player.sendMessage("§a已传送到出生点！");
                break;
                
            case "plugins":
                if (plugins.isEmpty()) {
                    player.sendMessage("§c当前没有加载任何插件");
                } else {
                    player.sendMessage("§a已加载的插件:");
                    plugins.values().forEach(plugin -> 
                        player.sendMessage("§7- §f" + plugin.getName() + " §7v" + plugin.getVersion())
                    );
                }
                break;
                
            case "help":
                player.sendMessage("§e========== 帮助 ==========");
                player.sendMessage("§f/spawn §7- 传送到出生点");
                player.sendMessage("§f/plugins §7- 查看插件列表");
                if (!commands.isEmpty()) {
                    commands.keySet().forEach(cmd -> 
                        player.sendMessage("§f/" + cmd)
                    );
                }
                player.sendMessage("§e==========================");
                break;
                
            default:
                player.sendMessage("§c未知命令: /" + command);
        }
    }

    // ============ 处理控制台命令 ============
    private static void handleConsoleCommand(String input) {
        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        switch (command) {
            case "stop":
                stop();
                System.exit(0);
                break;
                
            case "plugins":
                listPlugins();
                break;
                
            case "help":
                showHelp();
                break;
                
            case "reload":
                reloadPlugins();
                break;
                
            case "version":
                logger.info("Mintropy Server v" + VERSION + " (MC " + MC_VERSION + ")");
                break;
                
            case "players":
                listPlayers();
                break;
                
            case "broadcast":
                if (args.length > 0) {
                    String message = String.join(" ", args);
                    serverAPI.broadcastMessage("§c[广播] §f" + message);
                } else {
                    logger.info("用法: broadcast <消息>");
                }
                break;
                
            default:
                logger.info("未知命令: " + command + " (输入 'help' 查看帮助)");
        }
    }

    // ============ 列出在线玩家 ============
    private static void listPlayers() {
        Collection<Player> players = MinecraftServer.getConnectionManager().getOnlinePlayers();
        if (players.isEmpty()) {
            logger.info("当前没有在线玩家");
            return;
        }
        
        logger.info("在线玩家 (" + players.size() + "):");
        players.forEach(player -> 
            logger.info("  • " + player.getUsername() + " @ " + player.getPosition())
        );
    }

    // ============ 加载插件 ============
    public static void loadPlugins(String pluginDirectory) {
        File pluginDir = new File(pluginDirectory);
        
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
            logger.info("创建插件目录: " + pluginDir.getAbsolutePath());
            return;
        }
        
        File[] jarFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        
        if (jarFiles == null || jarFiles.length == 0) {
            logger.info("未找到插件文件");
            return;
        }
        
        logger.info("发现 " + jarFiles.length + " 个插件文件");
        
        for (File jarFile : jarFiles) {
            loadPlugin(jarFile);
        }
    }

    // ============ 加载单个插件 ============
    private static void loadPlugin(File jarFile) {
        try {
            PluginConfig config = readPluginConfig(jarFile);
            if (config == null) {
                logger.warning("跳过插件: " + jarFile.getName() + " (缺少有效的plugin.yml)");
                return;
            }
            
            PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                PluginServer.class.getClassLoader()
            );
            
            // 检查是否是Bukkit插件
            try {
                classLoader.loadClass("org.bukkit.plugin.java.JavaPlugin");
                logger.warning("跳过Bukkit插件: " + jarFile.getName());
                classLoader.close();
                return;
            } catch (ClassNotFoundException e) {
                // 不是Bukkit插件
            }
            
            Class<?> pluginClass;
            try {
                pluginClass = classLoader.loadClass(config.mainClass);
            } catch (ClassNotFoundException e) {
                logger.warning("找不到插件主类: " + config.mainClass);
                classLoader.close();
                return;
            }
            
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                logger.warning("插件主类必须实现 Plugin 接口: " + config.mainClass);
                classLoader.close();
                return;
            }
            
            Plugin plugin;
            try {
                plugin = (Plugin) pluginClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                logger.warning("无法实例化插件: " + config.mainClass + " - " + e.getMessage());
                classLoader.close();
                return;
            }
            
            injectServerAPI(plugin, serverAPI);
            
            plugins.put(plugin.getName(), plugin);
            classLoaders.put(plugin.getName(), classLoader);
            
            logger.info("✓ 加载插件: " + plugin.getName() + " v" + plugin.getVersion());
            
        } catch (Exception e) {
            logger.severe("✗ 加载插件失败: " + jarFile.getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============ 读取插件配置 ============
    private static PluginConfig readPluginConfig(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                logger.warning("插件缺少 plugin.yml: " + jarFile.getName());
                return null;
            }
            
            try (InputStream input = jar.getInputStream(entry)) {
                Properties props = new Properties();
                props.load(input);
                
                String name = props.getProperty("name");
                String version = props.getProperty("version");
                String main = props.getProperty("main");
                
                if (name == null || version == null || main == null) {
                    logger.warning("plugin.yml 缺少必要字段: " + jarFile.getName());
                    return null;
                }
                
                PluginConfig config = new PluginConfig(name, version, main);
                config.description = props.getProperty("description");
                config.author = props.getProperty("author");
                
                return config;
            }
        } catch (IOException e) {
            logger.warning("读取 plugin.yml 失败: " + e.getMessage());
            return null;
        }
    }

    // ============ 注入服务器API ============
    private static void injectServerAPI(Plugin plugin, ServerAPI api) {
        try {
            Arrays.stream(plugin.getClass().getMethods())
                .filter(method -> method.getName().equals("setServerAPI"))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0].isAssignableFrom(ServerAPI.class))
                .findFirst()
                .ifPresent(method -> {
                    try {
                        method.invoke(plugin, api);
                    } catch (Exception e) {
                        logger.warning("注入ServerAPI失败: " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            logger.warning("注入ServerAPI时出错: " + e.getMessage());
        }
    }

    // ============ 启用所有插件 ============
    public static void enableAllPlugins() {
        plugins.forEach((name, plugin) -> {
            try {
                plugin.onEnable();
                logger.info("✓ 启用插件: " + plugin.getName());
            } catch (Exception e) {
                logger.severe("✗ 启用插件失败: " + plugin.getName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // ============ 禁用所有插件 ============
    public static void disableAllPlugins() {
        plugins.forEach((name, plugin) -> {
            try {
                plugin.onDisable();
                logger.info("禁用插件: " + plugin.getName());
            } catch (Exception e) {
                logger.severe("禁用插件失败: " + plugin.getName() + " - " + e.getMessage());
            }
        });
        plugins.clear();
    }

    // ============ 停止服务器 ============
    public static void stop() {
        if (!running) return;
        logger.info("正在关闭服务器...");
        running = false;
        
        disableAllPlugins();
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        classLoaders.values().forEach(loader -> {
            try {
                loader.close();
            } catch (IOException e) {
                logger.warning("关闭类加载器失败: " + e.getMessage());
            }
        });
        
        MinecraftServer.stopCleanly();
        logger.info("服务器已关闭");
    }

    // ============ 列出所有插件 ============
    private static void listPlugins() {
        if (plugins.isEmpty()) {
            logger.info("当前没有加载任何插件");
            return;
        }
        
        logger.info("已加载的插件 (" + plugins.size() + "):");
        plugins.values().forEach(plugin -> 
            logger.info("  • " + plugin.getName() + " v" + plugin.getVersion())
        );
    }

    // ============ 重新加载插件 ============
    private static void reloadPlugins() {
        logger.info("重新加载插件...");
        disableAllPlugins();
        plugins.clear();
        commands.clear();
        loadPlugins("plugins");
        enableAllPlugins();
        logger.info("插件重新加载完成");
    }

    // ============ 打印横幅 ============
    private static void printBanner() {
        System.out.println("=================================");
        System.out.println("   Mintropy MC Server v" + VERSION);
        System.out.println("   插件化Minecraft服务器");
        System.out.println("   MC版本: " + MC_VERSION);
        System.out.println("=================================");
        System.out.flush();
    }

    // ============ 显示帮助 ============
    private static void showHelp() {
        System.out.println("\n========== 控制台命令 ==========");
        System.out.println("  stop       - 停止服务器");
        System.out.println("  plugins    - 列出所有插件");
        System.out.println("  reload     - 重新加载插件");
        System.out.println("  players    - 列出在线玩家");
        System.out.println("  broadcast  - 广播消息");
        System.out.println("  version    - 显示版本信息");
        System.out.println("  help       - 显示此帮助");
        System.out.println("================================\n");
        System.out.flush();
    }
}
