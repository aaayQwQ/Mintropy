import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;

/**
 * Mintropy MC Server - 纯Java高性能Minecraft服务器
 * 支持地形生成、玩家装备、建筑系统
 * 无验证，性能优先
 * 
 * @version 4.0.0
 */
public class PluginServer {
    private static final Logger logger = Logger.getLogger("Mintropy");
    private static String VERSION = "4.0.0";
    private static String MC_VERSION = "1.20.4";
    private static int PROTOCOL_VERSION = 765;
    private static int PORT = 25565;
    private static String SERVER_NAME = "Mintropy Server";
    private static int MAX_PLAYERS = 100;
    private static String MOTD = "§a§lMintropy Server §r§7- 高性能MC服务器";
    private static int VIEW_DISTANCE = 8;
    private static int SIMULATION_DISTANCE = 8;
    
    private static final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private static final Map<String, CommandExecutor> commands = new ConcurrentHashMap<>();
    private static final Map<String, MCPlayer> onlinePlayers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    
    private static ServerSocket serverSocket;
    private static ServerAPI serverAPI;
    private static volatile boolean running = false;
    private static Properties serverConfig = new Properties();
    
    // 世界数据
    private static World world;
    private static File worldFolder;

    // ============ 世界类 ============
    public static class World {
        private final String name;
        private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
        private final File worldFolder;
        private final Random random = new Random();
        
        public World(String name, File folder) {
            this.name = name;
            this.worldFolder = folder;
            worldFolder.mkdirs();
        }
        
        public Chunk getChunk(int chunkX, int chunkZ) {
            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            return chunks.computeIfAbsent(key, k -> loadOrGenerateChunk(chunkX, chunkZ));
        }
        
        private Chunk loadOrGenerateChunk(int chunkX, int chunkZ) {
            File chunkFile = new File(worldFolder, "chunk_" + chunkX + "_" + chunkZ + ".dat");
            
            if (chunkFile.exists()) {
                return loadChunk(chunkFile);
            } else {
                return generateChunk(chunkX, chunkZ, chunkFile);
            }
        }
        
        private Chunk generateChunk(int chunkX, int chunkZ, File file) {
            Chunk chunk = new Chunk(chunkX, chunkZ);
            
            // 生成地形
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;
                    
                    // 简单地形生成
                    int height = 64 + (int)(Math.sin(worldX * 0.1) * Math.cos(worldZ * 0.1) * 5);
                    
                    for (int y = 0; y <= height; y++) {
                        String blockType;
                        if (y == height) {
                            blockType = "minecraft:grass_block";
                        } else if (y > height - 3) {
                            blockType = "minecraft:dirt";
                        } else {
                            blockType = "minecraft:stone";
                        }
                        chunk.setBlock(x, y, z, blockType);
                    }
                    
                    // 生成树
                    if (random.nextInt(100) < 5 && height > 64) {
                        generateTree(chunk, x, height + 1, z);
                    }
                }
            }
            
            // 保存区块
            saveChunk(chunk, file);
            return chunk;
        }
        
        private void generateTree(Chunk chunk, int x, int y, int z) {
            // 树干
            for (int i = 0; i < 4; i++) {
                chunk.setBlock(x, y + i, z, "minecraft:oak_log");
            }
            // 树叶
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 2; dy <= 4; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 3) <= 3) {
                            chunk.setBlock(x + dx, y + dy, z + dz, "minecraft:oak_leaves");
                        }
                    }
                }
            }
        }
        
        private void saveChunk(Chunk chunk, File file) {
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
                chunk.save(output);
            } catch (IOException e) {
                logger.warning("保存区块失败: " + e.getMessage());
            }
        }
        
        private Chunk loadChunk(File file) {
            Chunk chunk = new Chunk(0, 0);
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                chunk.load(input);
            } catch (IOException e) {
                logger.warning("加载区块失败: " + e.getMessage());
            }
            return chunk;
        }
        
        public void saveAll() {
            chunks.forEach((key, chunk) -> {
                File chunkFile = new File(worldFolder, "chunk_" + chunk.chunkX + "_" + chunk.chunkZ + ".dat");
                saveChunk(chunk, chunkFile);
            });
        }
    }

    // ============ 区块类 ============
    public static class Chunk {
        private final int chunkX;
        private final int chunkZ;
        private final String[][][] blocks = new String[16][256][16];
        
        public Chunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
        
        public void setBlock(int x, int y, int z, String blockType) {
            if (x >= 0 && x < 16 && y >= 0 && y < 256 && z >= 0 && z < 16) {
                blocks[x][y][z] = blockType;
            }
        }
        
        public String getBlock(int x, int y, int z) {
            if (x >= 0 && x < 16 && y >= 0 && y < 256 && z >= 0 && z < 16) {
                return blocks[x][y][z];
            }
            return null;
        }
        
        public void save(DataOutputStream output) throws IOException {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        String block = blocks[x][y][z];
                        if (block != null) {
                            output.writeBoolean(true);
                            output.writeUTF(block);
                        } else {
                            output.writeBoolean(false);
                        }
                    }
                }
            }
        }
        
        public void load(DataInputStream input) throws IOException {
            input.readInt(); // chunkX
            input.readInt(); // chunkZ
            
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (input.readBoolean()) {
                            blocks[x][y][z] = input.readUTF();
                        }
                    }
                }
            }
        }
    }

    // ============ MC玩家类 ============
    public static class MCPlayer {
        private final String username;
        private final UUID uuid;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private double x = 0, y = 64, z = 0;
        private float yaw = 0, pitch = 0;
        private boolean onGround = true;
        
        // 装备栏
        private final String[] equipment = new String[6]; // 0=主手,1=副手,2=头盔,3=胸甲,4=护腿,5=靴子
        // 背包
        private final String[] inventory = new String[36];
        // 生命值
        private float health = 20.0f;
        private float maxHealth = 20.0f;
        // 饥饿值
        private int foodLevel = 20;
        // 经验
        private int experience = 0;
        private int level = 0;
        
        public MCPlayer(String username, UUID uuid, Socket socket) throws IOException {
            this.username = username;
            this.uuid = uuid;
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
        }
        
        public String getUsername() { return username; }
        public UUID getUUID() { return uuid; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        
        public void teleport(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            sendPlayerPosition();
        }
        
        public void sendMessage(String message) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                writeVarInt(packet, 0x65);
                writeString(packet, message);
                writeVarInt(packet, 0);
                
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                logger.warning("发送消息失败: " + e.getMessage());
            }
        }
        
        public void setEquipment(int slot, String item) {
            if (slot >= 0 && slot < equipment.length) {
                equipment[slot] = item;
                sendEquipmentUpdate(slot, item);
            }
        }
        
        public void setInventoryItem(int slot, String item) {
            if (slot >= 0 && slot < inventory.length) {
                inventory[slot] = item;
                sendInventoryUpdate(slot, item);
            }
        }
        
        private void sendEquipmentUpdate(int slot, String item) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                writeVarInt(packet, 0x5B); // Set Equipment
                writeVarInt(packet, 0); // Entity ID (self)
                writeVarInt(packet, slot);
                writeItemStack(packet, item);
                
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                logger.warning("发送装备更新失败: " + e.getMessage());
            }
        }
        
        private void sendInventoryUpdate(int slot, String item) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                writeVarInt(packet, 0x14); // Set Container Slot
                packet.writeByte(0); // Window ID
                writeVarInt(packet, 0); // State ID
                packet.writeShort(slot);
                writeItemStack(packet, item);
                
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                logger.warning("发送背包更新失败: " + e.getMessage());
            }
        }
        
        private void writeItemStack(DataOutputStream packet, String item) throws IOException {
            if (item == null || item.isEmpty()) {
                packet.writeBoolean(false); // No item
            } else {
                packet.writeBoolean(true); // Has item
                writeVarInt(packet, 1); // Item ID (diamond sword = 1 for simplicity)
                packet.writeByte(1); // Count
                // No NBT data
                packet.writeByte(0); // End of NBT
            }
        }
        
        private void sendPlayerPosition() {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                
                writeVarInt(packet, 0x3E);
                packet.writeDouble(x);
                packet.writeDouble(y);
                packet.writeDouble(z);
                packet.writeFloat(yaw);
                packet.writeFloat(pitch);
                packet.writeByte(0);
                writeVarInt(packet, 0);
                
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
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                writeVarInt(packet, 0x1B);
                writeString(packet, reason);
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                // 忽略
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
        World getWorld();
        void setBlock(int x, int y, int z, String blockType);
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
        
        @Override
        public World getWorld() {
            return world;
        }
        
        @Override
        public void setBlock(int x, int y, int z, String blockType) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            Chunk chunk = world.getChunk(chunkX, chunkZ);
            chunk.setBlock(x & 15, y, z & 15, blockType);
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
        
        // 加载配置
        loadConfig();
        
        // 加载世界
        loadWorld();
        
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

    // ============ 加载配置 ============
    private static void loadConfig() {
        File configFile = new File("Mintropyserver.properties");
        
        if (!configFile.exists()) {
            logger.info("创建默认配置文件: Mintropyserver.properties");
            try (FileOutputStream output = new FileOutputStream(configFile)) {
                Properties defaultConfig = new Properties();
                defaultConfig.setProperty("server-name", SERVER_NAME);
                defaultConfig.setProperty("server-port", String.valueOf(PORT));
                defaultConfig.setProperty("max-players", String.valueOf(MAX_PLAYERS));
                defaultConfig.setProperty("motd", MOTD);
                defaultConfig.setProperty("mc-version", MC_VERSION);
                defaultConfig.setProperty("view-distance", String.valueOf(VIEW_DISTANCE));
                defaultConfig.setProperty("simulation-distance", String.valueOf(SIMULATION_DISTANCE));
                defaultConfig.setProperty("world-name", "MintropyWorld");
                defaultConfig.store(output, "Mintropy Server Configuration");
            } catch (IOException e) {
                logger.warning("创建配置文件失败: " + e.getMessage());
            }
            return;
        }
        
        try (FileInputStream input = new FileInputStream(configFile)) {
            serverConfig.load(input);
            
            SERVER_NAME = serverConfig.getProperty("server-name", SERVER_NAME);
            PORT = Integer.parseInt(serverConfig.getProperty("server-port", String.valueOf(PORT)));
            MAX_PLAYERS = Integer.parseInt(serverConfig.getProperty("max-players", String.valueOf(MAX_PLAYERS)));
            MOTD = serverConfig.getProperty("motd", MOTD);
            MC_VERSION = serverConfig.getProperty("mc-version", MC_VERSION);
            VIEW_DISTANCE = Integer.parseInt(serverConfig.getProperty("view-distance", String.valueOf(VIEW_DISTANCE)));
            SIMULATION_DISTANCE = Integer.parseInt(serverConfig.getProperty("simulation-distance", String.valueOf(SIMULATION_DISTANCE)));
            
            logger.info("配置文件加载完成: " + configFile.getAbsolutePath());
            logger.info("服务器名称: " + SERVER_NAME);
            logger.info("端口: " + PORT);
            logger.info("最大玩家数: " + MAX_PLAYERS);
            
        } catch (IOException e) {
            logger.warning("读取配置文件失败: " + e.getMessage());
        }
    }

    // ============ 加载世界 ============
    private static void loadWorld() {
        String worldName = serverConfig.getProperty("world-name", "MintropyWorld");
        worldFolder = new File(worldName);
        
        if (worldFolder.exists()) {
            logger.info("加载现有世界: " + worldName);
        } else {
            logger.info("生成新世界: " + worldName);
            worldFolder.mkdirs();
        }
        
        world = new World(worldName, worldFolder);
        
        // 预生成出生点附近区块
        logger.info("预生成出生点区块...");
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getChunk(dx, dz);
            }
        }
        logger.info("世界加载完成");
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
                
                int packetLength = readVarInt(input);
                byte[] packetData = new byte[packetLength];
                input.readFully(packetData);
                
                DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
                int packetId = readVarInt(packet);
                
                if (packetId != 0x00) {
                    socket.close();
                    return;
                }
                
                int protocolVersion = readVarInt(packet);
                String serverAddress = readString(packet);
                int serverPort = packet.readUnsignedShort();
                int nextState = readVarInt(packet);
                
                if (nextState == 1) {
                    handleStatusRequest(input, output);
                } else if (nextState == 2) {
                    handleLoginRequest(socket, input, output);
                }
                
            } catch (Exception e) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    // 忽略
                }
            }
        });
        clientThread.setDaemon(true);
        clientThread.start();
    }

    // ============ 处理状态请求 ============
    private static void handleStatusRequest(DataInputStream input, DataOutputStream output) throws IOException {
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);
        
        String statusJson = "{\"version\":{\"name\":\"" + MC_VERSION + "\",\"protocol\":" + PROTOCOL_VERSION + "}," +
                           "\"players\":{\"max\":" + MAX_PLAYERS + ",\"online\":" + onlinePlayers.size() + "}," +
                           "\"description\":{\"text\":\"" + MOTD + "\"}}";
        
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x00);
        writeString(packet, statusJson);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
        
        packetLength = readVarInt(input);
        packetData = new byte[packetLength];
        input.readFully(packetData);
        
        buffer = new ByteArrayOutputStream();
        packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x01);
        packet.write(packetData);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ============ 处理登录请求 ============
    private static void handleLoginRequest(Socket socket, DataInputStream input, DataOutputStream output) throws IOException {
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);
        
        DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
        int packetId = readVarInt(packet);
        
        if (packetId != 0x00) {
            socket.close();
            return;
        }
        
        String username = readString(packet);
        UUID playerUUID = UUID.randomUUID();
        
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream response = new DataOutputStream(buffer);
        writeVarInt(response, 0x02);
        writeString(response, playerUUID.toString());
        writeString(response, username);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
        
        MCPlayer player = new MCPlayer(username, playerUUID, socket);
        onlinePlayers.put(username, player);
        
        sendJoinGame(output);
        sendPlayerPositionAndLook(output);
        
        logger.info("玩家 " + username + " 已加入游戏！");
        serverAPI.broadcastMessage("§e" + username + " §a加入了服务器");
        
        handleGamePackets(player);
    }

    // ============ 发送加入游戏包 ============
    private static void sendJoinGame(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        
        writeVarInt(packet, 0x2B);
        packet.writeInt(0);
        packet.writeBoolean(false);
        packet.writeByte(1);
        packet.writeByte(-1);
        writeVarInt(packet, 1);
        writeString(packet, "minecraft:overworld");
        writeString(packet, "minecraft:overworld");
        writeString(packet, world.name);
        packet.writeLong(0);
        packet.writeByte(MAX_PLAYERS);
        writeVarInt(packet, VIEW_DISTANCE);
        writeVarInt(packet, SIMULATION_DISTANCE);
        packet.writeBoolean(false);
        packet.writeBoolean(true);
        packet.writeBoolean(false);
        packet.writeBoolean(false);
        packet.writeBoolean(false);
        
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ============ 发送玩家位置 ============
    private static void sendPlayerPositionAndLook(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        
        writeVarInt(packet, 0x3E);
        packet.writeDouble(0);
        packet.writeDouble(65);
        packet.writeDouble(0);
        packet.writeFloat(0);
        packet.writeFloat(0);
        packet.writeByte(0);
        writeVarInt(packet, 0);
        
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
                        player.onGround = packet.readBoolean();
                        break;
                        
                    case 0x1B: // Player Rotation
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        player.onGround = packet.readBoolean();
                        break;
                        
                    case 0x1C: // Player Position and Rotation
                        player.x = packet.readDouble();
                        player.y = packet.readDouble();
                        player.z = packet.readDouble();
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        player.onGround = packet.readBoolean();
                        break;
                        
                    case 0x1D: // Player Action (挖掘方块等)
                        int action = readVarInt(packet);
                        int blockX = packet.readInt();
                        int blockY = packet.readInt();
                        int blockZ = packet.readInt();
                        handleBlockAction(player, action, blockX, blockY, blockZ);
                        break;
                        
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            onlinePlayers.remove(player.getUsername());
            logger.info("玩家 " + player.getUsername() + " 断开连接");
            serverAPI.broadcastMessage("§e" + player.getUsername() + " §c离开了服务器");
        }
    }

    // ============ 处理方块操作 ============
    private static void handleBlockAction(MCPlayer player, int action, int x, int y, int z) {
        switch (action) {
            case 0: // 开始挖掘
                // 移除方块
                serverAPI.setBlock(x, y, z, "minecraft:air");
                break;
            case 1: // 取消挖掘
                break;
            case 2: // 完成挖掘
                serverAPI.setBlock(x, y, z, "minecraft:air");
                break;
            case 3: // 放置方块
                // 简化处理，在点击位置放置石头
                serverAPI.setBlock(x, y + 1, z, "minecraft:stone");
                break;
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
                player.teleport(0, 65, 0);
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
                }
                break;
                
            case "save":
                world.saveAll();
                logger.info("世界已保存");
                break;
                
            default:
                logger.info("未知命令: " + command);
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
        
        for (File jarFile : jarFiles) {
            loadPlugin(jarFile);
        }
    }

    // ============ 加载单个插件 ============
    private static void loadPlugin(File jarFile) {
        try {
            PluginConfig config = readPluginConfig(jarFile);
            if (config == null) return;
            
            PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                PluginServer.class.getClassLoader()
            );
            
            Class<?> pluginClass = classLoader.loadClass(config.mainClass);
            
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                classLoader.close();
                return;
            }
            
            Plugin plugin = (Plugin) pluginClass.getDeclaredConstructor().newInstance();
            injectServerAPI(plugin, serverAPI);
            
            plugins.put(plugin.getName(), plugin);
            classLoaders.put(plugin.getName(), classLoader);
            
            logger.info("✓ 加载插件: " + plugin.getName() + " v" + plugin.getVersion());
            
        } catch (Exception e) {
            logger.severe("✗ 加载插件失败: " + jarFile.getName() + " - " + e.getMessage());
        }
    }

    // ============ 读取插件配置 ============
    private static PluginConfig readPluginConfig(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;
            
            try (InputStream input = jar.getInputStream(entry)) {
                Properties props = new Properties();
                props.load(input);
                
                String name = props.getProperty("name");
                String version = props.getProperty("version");
                String
