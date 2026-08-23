import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;


/**
 * Mintropy MC Server - 纯Java实现的Minecraft服务器
 * 完全独立，无第三方依赖
 * 支持MC客户端连接和插件扩展
 * 
 * @version 3.0.0
 */
public class PluginServer {
    private static final Logger logger = Logger.getLogger("Mintropy");
    private static final String VERSION = "3.0.0";
    private static final String MC_VERSION = "1.20.4";
    private static final int PROTOCOL_VERSION = 765;
    private static final int PORT = 25565;
    
    private static final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private static final Map<String, CommandExecutor> commands = new ConcurrentHashMap<>();
    private static final Map<String, MCPlayer> onlinePlayers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    
    private static ServerSocket serverSocket;
    private static ServerAPI serverAPI;
    private static volatile boolean running = false;

    // ============ MC玩家类 ============
    public static class MCPlayer {
        private final String username;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private double x = 0, y = 64, z = 0;
        private float yaw = 0, pitch = 0;
        
        public MCPlayer(String username, Socket socket) throws IOException {
            this.username = username;
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
        }
        
        public String getUsername() { return username; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        
        public void teleport(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            // 发送传送包
            sendPlayerPosition();
        }
        
        public void sendMessage(String message) {
            try {
                // 发送聊天消息包 (0x65)
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                // 包ID (0x65 = System Chat Message)
                writeVarInt(packet, 0x65);
                // 消息内容
                writeString(packet, message);
                // 消息类型 (0 = SYSTEM)
                writeVarInt(packet, 0);
                
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                logger.warning("发送消息失败: " + e.getMessage());
            }
        }
        
        private void sendPlayerPosition() {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                // 包ID (0x3E = Synchronize Player Position)
                writeVarInt(packet, 0x3E);
                packet.writeDouble(x);
                packet.writeDouble(y);
                packet.writeDouble(z);
                packet.writeFloat(yaw);
                packet.writeFloat(pitch);
                packet.writeByte(0); // flags
                writeVarInt(packet, 0); // teleport ID
                
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                logger.warning("发送位置失败: " + e.getMessage());
            }
        }
        
        private void sendPacket(byte[] data) throws IOException {
            synchronized (output) {
                writeVarInt(output, data.length);
                output.write(data);
                output.flush();
            }
        }
        
        public void disconnect(String reason) {
            try {
                // 发送断开包
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                writeVarInt(packet, 0x1B); // Disconnect packet
                writeString(packet, reason);
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                // 忽略断开错误
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // 忽略
                }
            }
        }
    }

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
        void onCommand(MCPlayer player, String[] args);
    }

    // ============ 服务器API接口 ============
    public interface ServerAPI {
        void registerCommand(String command, CommandExecutor executor);
        void broadcastMessage(String message);
        void scheduleTask(Runnable task, long delay);
        Logger getLogger();
        Collection<MCPlayer> getOnlinePlayers();
        void teleportPlayer(MCPlayer player, double x, double y, double z);
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
            onlinePlayers.values().forEach(player -> player.sendMessage(message));
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
        public Collection<MCPlayer> getOnlinePlayers() {
            return onlinePlayers.values();
        }

        @Override
        public void teleportPlayer(MCPlayer player, double x, double y, double z) {
            player.teleport(x, y, z);
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
        
        // 初始化服务器API
        serverAPI = new ServerAPIImpl();
        
        // 加载插件
        loadPlugins("plugins");
        enableAllPlugins();
        
        // 启动MC服务器
        startMCServer();
        
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

    // ============ 启动MC服务器 ============
    private static void startMCServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            running = true;
            
            logger.info("Mintropy MC 服务器启动完成！");
            logger.info("MC版本: " + MC_VERSION);
            logger.info("端口: " + PORT);
            logger.info("等待客户端连接...");
            logger.info("输入 'help' 查看可用命令");
            
            // 接受客户端连接
            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handleClientConnection(clientSocket);
                    } catch (IOException e) {
                        if (running) {
                            logger.warning("接受连接失败: " + e.getMessage());
                        }
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.setName("AcceptThread");
            acceptThread.start();
            
        } catch (IOException e) {
            logger.severe("启动MC服务器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============ 处理客户端连接 ============
    private static void handleClientConnection(Socket socket) {
        Thread clientThread = new Thread(() -> {
            try {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                
                // 读取握手包
                int packetLength = readVarInt(input);
                byte[] packetData = new byte[packetLength];
                input.readFully(packetData);
                
                DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
                int packetId = readVarInt(packet);
                
                if (packetId != 0x00) {
                    logger.warning("期望握手包，收到: 0x" + Integer.toHexString(packetId));
                    socket.close();
                    return;
                }
                
                // 解析握手包
                int protocolVersion = readVarInt(packet);
                String serverAddress = readString(packet);
                int serverPort = packet.readUnsignedShort();
                int nextState = readVarInt(packet);
                
                logger.info("握手: 协议=" + protocolVersion + ", 地址=" + serverAddress + ", 状态=" + nextState);
                
                if (nextState == 1) {
                    // Status请求
                    handleStatusRequest(input, output);
                } else if (nextState == 2) {
                    // Login请求
                    handleLoginRequest(socket, input, output);
                } else {
                    socket.close();
                }
                
            } catch (Exception e) {
                logger.warning("处理客户端连接失败: " + e.getMessage());
                try {
                    socket.close();
                } catch (IOException ex) {
                    // 忽略
                }
            }
        });
        clientThread.setDaemon(true);
        clientThread.setName("ClientThread");
        clientThread.start();
    }

    // ============ 处理状态请求 ============
    private static void handleStatusRequest(DataInputStream input, DataOutputStream output) throws IOException {
        // 读取状态请求包
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);
        
        // 发送状态响应
        String statusJson = "{\"version\":{\"name\":\"" + MC_VERSION + "\",\"protocol\":" + PROTOCOL_VERSION + "}," +
                           "\"players\":{\"max\":100,\"online\":" + onlinePlayers.size() + "}," +
                           "\"description\":{\"text\":\"§a§lMintropy Server\"}}";
        
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x00); // Status Response
        writeString(packet, statusJson);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
        
        // 等待Ping
        packetLength = readVarInt(input);
        packetData = new byte[packetLength];
        input.readFully(packetData);
        
        // 发送Pong
        buffer = new ByteArrayOutputStream();
        packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x01); // Pong
        packet.write(packetData); // Echo back
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ============ 处理登录请求 ============
    private static void handleLoginRequest(Socket socket, DataInputStream input, DataOutputStream output) throws IOException {
        // 读取登录开始包
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);
        
        DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
        int packetId = readVarInt(packet);
        
        if (packetId != 0x00) {
            logger.warning("期望登录开始包");
            socket.close();
            return;
        }
        
        String username = readString(packet);
        logger.info("玩家登录: " + username);
        
        // 发送登录成功包
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream response = new DataOutputStream(buffer);
        writeVarInt(response, 0x02); // Login Success
        writeString(response, java.util.UUID.randomUUID().toString());
        writeString(response, username);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
        
        // 创建玩家对象
        MCPlayer player = new MCPlayer(username, socket);
        onlinePlayers.put(username, player);
        
        // 发送加入游戏包
        sendJoinGame(output);
        
        // 发送玩家位置
        sendPlayerPositionAndLook(output);
        
        logger.info("玩家 " + username + " 已加入游戏！");
        serverAPI.broadcastMessage("§e" + username + " §a加入了服务器");
        
        // 处理游戏内数据包
        handleGamePackets(player);
    }

    // ============ 发送加入游戏包 ============
    private static void sendJoinGame(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        
        writeVarInt(packet, 0x2B); // Join Game
        packet.writeInt(0); // Entity ID
        packet.writeBoolean(false); // Is hardcore
        packet.writeByte(1); // Gamemode (Creative)
        packet.writeByte(-1); // Previous gamemode
        writeVarInt(packet, 1); // Dimension count
        writeString(packet, "minecraft:overworld"); // Dimension name
        writeString(packet, "minecraft:overworld"); // Dimension type
        writeString(packet, "minecraft:overworld"); // World name
        packet.writeLong(0); // Hashed seed
        packet.writeByte(100); // Max players
        writeVarInt(packet, 10); // View distance
        writeVarInt(packet, 10); // Simulation distance
        packet.writeBoolean(false); // Reduced debug info
        packet.writeBoolean(true); // Enable respawn screen
        packet.writeBoolean(false); // Is debug
        packet.writeBoolean(false); // Is flat
        packet.writeBoolean(false); // Has death location
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ============ 发送玩家位置 ============
    private static void sendPlayerPositionAndLook(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        
        writeVarInt(packet, 0x3E); // Synchronize Player Position
        packet.writeDouble(0); // X
        packet.writeDouble(64); // Y
        packet.writeDouble(0); // Z
        packet.writeFloat(0); // Yaw
        packet.writeFloat(0); // Pitch
        packet.writeByte(0); // Flags
        writeVarInt(packet, 0); // Teleport ID
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ============ 处理游戏内数据包 ============
    private static void handleGamePackets(MCPlayer player) {
        try {
            DataInputStream input = player.input;
            
            while (running && !player.socket.isClosed()) {
                int packetLength = readVarInt(input);
                byte[] packetData = new byte[packetLength];
                input.readFully(packetData);
                
                DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
                int packetId = readVarInt(packet);
                
                switch (packetId) {
                    case 0x05: // Chat Message
                        String message = readString(packet);
                        handlePlayerChat(player, message);
                        break;
                        
                    case 0x1A: // Player Position
                        player.x = packet.readDouble();
                        player.y = packet.readDouble();
                        player.z = packet.readDouble();
                        break;
                        
                    case 0x1B: // Player Rotation
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        break;
                        
                    case 0x1C: // Player Position and Rotation
                        player.x = packet.readDouble();
                        player.y = packet.readDouble();
                        player.z = packet.readDouble();
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        break;
                        
                    default:
                        // 忽略其他包
                        break;
                }
            }
        } catch (IOException e) {
            // 玩家断开连接
            onlinePlayers.remove(player.getUsername());
            logger.info("玩家 " + player.getUsername() + " 断开连接");
            serverAPI.broadcastMessage("§e" + player.getUsername() + " §c离开了服务器");
        }
    }

    // ============ 处理玩家聊天 ============
    private static void handlePlayerChat(MCPlayer player, String message) {
        if (message.startsWith("/")) {
            handlePlayerCommand(player, message.substring(1));
        } else {
            String formattedMessage = "§7<§f" + player.getUsername() + "§7> §f" + message;
            serverAPI.broadcastMessage(formattedMessage);
            logger.info("[聊天] " + player.getUsername() + ": " + message);
        }
    }

    // ============ 处理玩家命令 ============
    private static void handlePlayerCommand(MCPlayer player, String commandLine) {
        String[] parts = commandLine.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        CommandExecutor executor = commands.get(command);
        if (executor != null) {
            try {
                executor.onCommand(player, args);
            } catch (Exception e) {
                player.sendMessage("§c命令执行错误: " + e.getMessage());
            }
            return;
        }
        
        switch (command) {
            case "spawn":
                player.teleport(0, 64, 0);
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
        if (onlinePlayers.isEmpty()) {
            logger.info("当前没有在线玩家");
            return;
        }
        
        logger.info("在线玩家 (" + onlinePlayers.size() + "):");
        onlinePlayers.values().forEach(player -> 
            logger.info("  • " + player.getUsername() + " @ (" + 
                       player.getX() + ", " + player.getY() + ", " + player.getZ() + ")")
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
        
        // 断开所有玩家
        onlinePlayers.values().forEach(player -> player.disconnect("§c服务器关闭"));
        onlinePlayers.clear();
        
        // 关闭服务器Socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warning("关闭服务器Socket失败: " + e.getMessage());
        }
        
        classLoaders.values().forEach(loader -> {
            try {
                loader.close();
            } catch (IOException e) {
                logger.warning("关闭类加载器失败: " + e.getMessage());
            }
        });
        
        logger.info("服务器已关闭");
    }

    // ============ 工具方法 ============
    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        int position = 0;
        byte currentByte;
        
        do {
            currentByte = input.readByte();
            result |= (currentByte & 0x7F) << position;
            position += 7;
            
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        } while ((currentByte & 0x80) != 0);
        
        return result;
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            byte temp = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                temp |= 0x80;
            }
            output.writeByte(temp);
        } while (value != 0);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
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
        System.out.println("   纯Java实现的Minecraft服务器");
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
