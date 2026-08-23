import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.logging.*;

/**
 * PluginServer - 插件化服务器
 * 支持动态加载JAR插件，提供简单的API接口
 * 
 * @version 1.0.0
 */
public class PluginServer {
    private static final Logger logger = Logger.getLogger("PluginServer");
    private static final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private static final Map<String, CommandExecutor> commands = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static volatile boolean running = false;
    private static ServerAPI serverAPI;
    private static final String VERSION = "1.0.0";

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
        void onCommand(String command, String[] args);
    }

    // ============ 服务器API接口 ============
    public interface ServerAPI {
        void registerCommand(String command, CommandExecutor executor);
        void broadcastMessage(String message);
        void scheduleTask(Runnable task, long delay);
        Logger getLogger();
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
        start();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                stop();
            }
        }));
    }

    // ============ 打印横幅 ============
    private static void printBanner() {
        System.out.println("=================================");
        System.out.println("   Mintropy Server v" + VERSION);
        System.out.println("   插件化服务器");
        System.out.println("=================================");
    }

    // ============ 启动服务器 ============
    public static void start() {
        logger.info("正在启动插件服务器...");
        running = true;
        
        // 初始化服务器API
        serverAPI = new ServerAPIImpl();
        
        // 加载插件
        loadPlugins("plugins");
        enableAllPlugins();
        
        logger.info("插件服务器启动完成！");
        logger.info("输入 'help' 查看可用命令");
        
        // 启动控制台输入
        startConsoleInput();
    }

    // ============ 停止服务器 ============
    public static void stop() {
        if (!running) return;
        logger.info("正在关闭插件服务器...");
        running = false;
        
        // 禁用所有插件
        disableAllPlugins();
        
        // 关闭调度器
        scheduler.shutdown();
        
        // 关闭类加载器
        classLoaders.values().forEach(loader -> {
            try {
                loader.close();
            } catch (IOException e) {
                logger.warning("关闭类加载器失败: " + e.getMessage());
            }
        });
        
        logger.info("插件服务器已关闭");
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
            logger.info("未找到插件文件，将插件JAR放入 'plugins' 目录");
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
            // 读取plugin.yml配置
            PluginConfig config = readPluginConfig(jarFile);
            if (config == null) {
                logger.warning("跳过插件: " + jarFile.getName() + " (缺少有效的plugin.yml)");
                return;
            }
            
            // 创建类加载器
            PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                PluginServer.class.getClassLoader()
            );
            
            // 加载插件主类
            Class<?> pluginClass = classLoader.loadClass(config.mainClass);
            
            // 验证是否实现了Plugin接口
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                logger.warning("插件主类必须实现 Plugin 接口: " + config.mainClass);
                classLoader.close();
                return;
            }
            
            // 实例化插件
            Plugin plugin = (Plugin) pluginClass.getDeclaredConstructor().newInstance();
            
            // 注入服务器API
            injectServerAPI(plugin, serverAPI);
            
            // 注册插件
            plugins.put(plugin.getName(), plugin);
            classLoaders.put(plugin.getName(), classLoader);
            
            logger.info("✓ 加载插件: " + plugin.getName() + 
                       " v" + plugin.getVersion() + 
                       (config.author != null ? " by " + config.author : ""));
            
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
                    logger.warning("plugin.yml 缺少必要字段 (name, version, main): " + jarFile.getName());
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

    // ============ 启动控制台输入 ============
    private static void startConsoleInput() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (running) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                if (!input.isEmpty()) {
                    handleCommand(input);
                }
            }
            scanner.close();
        });
        consoleThread.setDaemon(true);
        consoleThread.setName("ConsoleInput");
        consoleThread.start();
    }

    // ============ 处理控制台命令 ============
    private static void handleCommand(String input) {
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
                logger.info("Mintropy Server v" + VERSION);
                break;
                
            default:
                CommandExecutor executor = commands.get(command);
                if (executor != null) {
                    try {
                        executor.onCommand(command, args);
                    } catch (Exception e) {
                        logger.severe("执行命令失败: " + command + " - " + e.getMessage());
                    }
                } else {
                    logger.info("未知命令: " + command + " (输入 'help' 查看帮助)");
                }
        }
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
        
        if (!commands.isEmpty()) {
            logger.info("已注册的命令:");
            commands.keySet().forEach(cmd -> 
                logger.info("  • /" + cmd)
            );
        }
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

    // ============ 显示帮助 ============
    private static void showHelp() {
        System.out.println("\n========== 可用命令 ==========");
        System.out.println("  stop     - 停止服务器");
        System.out.println("  plugins  - 列出所有插件");
        System.out.println("  reload   - 重新加载插件");
        System.out.println("  version  - 显示版本信息");
        System.out.println("  help     - 显示此帮助");
        
        if (!commands.isEmpty()) {
            System.out.println("\n插件命令:");
            commands.keySet().forEach(cmd -> 
                System.out.println("  /" + cmd)
            );
        }
        System.out.println("=============================\n");
    }
}
