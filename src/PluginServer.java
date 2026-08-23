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
 * 兼容 Minecraft 1.8.8 (协议47)
 * 无正版验证，性能优先
 * 
 * @version 7.0.0
 */
public class PluginServer {

    // ==================== 服务器基本信息 ====================
    private static final Logger logger = Logger.getLogger("Mintropy");
    private static String VERSION = "7.0.0";
    private static String MC_VERSION = "1.8.8";
    private static int PROTOCOL_VERSION = 47;
    private static int PORT = 25565;
    private static String SERVER_NAME = "Mintropy";
    private static int MAX_PLAYERS = 100;
    private static String MOTD = "Mintropy Server";
    private static int VIEW_DISTANCE = 8;

    // ==================== 核心集合 ====================
    private static final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private static final Map<String, CommandExecutor> commands = new ConcurrentHashMap<>();
    private static final Map<String, MCPlayer> onlinePlayers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);

    // ==================== 网络相关 ====================
    private static ServerSocket serverSocket;
    private static ServerAPI serverAPI;
    private static volatile boolean running = false;
    private static Properties serverConfig = new Properties();

    // ==================== 世界 ====================
    private static World world;
    private static File worldFolder;

    // ==================== 世界类 ====================
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
                return loadChunk(chunkFile, chunkX, chunkZ);
            } else {
                return generateChunk(chunkX, chunkZ, chunkFile);
            }
        }

        private Chunk generateChunk(int chunkX, int chunkZ, File file) {
            Chunk chunk = new Chunk(chunkX, chunkZ);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;

                    int height = 64 + (int)(Math.sin(worldX * 0.1) * Math.cos(worldZ * 0.1) * 5);

                    for (int y = 0; y <= height; y++) {
                        String blockType;
                        if (y == height) {
                            blockType = "grass_block";
                        } else if (y > height - 3) {
                            blockType = "dirt";
                        } else {
                            blockType = "stone";
                        }
                        chunk.setBlock(x, y, z, blockType);
                    }

                    if (random.nextInt(100) < 5 && height > 64) {
                        generateTree(chunk, x, height + 1, z);
                    }

                    if (random.nextInt(100) < 2) {
                        generateOreVein(chunk, x, z, height);
                    }
                }
            }

            saveChunk(chunk, file);
            return chunk;
        }

        private void generateTree(Chunk chunk, int x, int y, int z) {
            for (int i = 0; i < 4; i++) {
                chunk.setBlock(x, y + i, z, "oak_log");
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 2; dy <= 4; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 3) <= 3) {
                            chunk.setBlock(x + dx, y + dy, z + dz, "oak_leaves");
                        }
                    }
                }
            }
        }

        private void generateOreVein(Chunk chunk, int x, int z, int surfaceHeight) {
            int oreY = random.nextInt(Math.max(1, surfaceHeight - 5));
            String oreType = random.nextBoolean() ? "coal_ore" : "iron_ore";
            chunk.setBlock(x, oreY, z, oreType);
        }

        private void saveChunk(Chunk chunk, File file) {
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
                chunk.save(output);
            } catch (IOException e) {
                logger.warning("保存区块失败: " + e.getMessage());
            }
        }

        private Chunk loadChunk(File file, int chunkX, int chunkZ) {
            Chunk chunk = new Chunk(chunkX, chunkZ);
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
            logger.info("世界保存完成: " + chunks.size() + " 个区块");
        }
    }

    // ==================== 区块类 ====================
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
                            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
                            output.writeInt(bytes.length);
                            output.write(bytes);
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
                            int length = input.readInt();
                            byte[] bytes = new byte[length];
                            input.readFully(bytes);
                            blocks[x][y][z] = new String(bytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
    }

    // ==================== 玩家类 ====================
    public static class MCPlayer {
        private final String username;
        private final String uuid;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        private double x = 0, y = 64, z = 0;
        private float yaw = 0, pitch = 0;
        private boolean onGround = true;

        private final String[] equipment = new String[6];
        private final String[] inventory = new String[36];

        private float health = 20.0f;
        private float maxHealth = 20.0f;
        private int foodLevel = 20;
        private int experience = 0;
        private int level = 0;

        public MCPlayer(String username, String uuid, Socket socket) throws IOException {
            this.username = username;
            this.uuid = uuid;
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
        }

        public String getUsername() { return username; }
        public String getUUID() { return uuid; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }

        public void teleport(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            sendPlayerPositionAndLook();
        }

        public void sendMessage(String message) {
            try {
                if (message.length() > 100) {
                    message = message.substring(0, 100);
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                writeVarInt(packet, 0x02); // Chat Message (clientbound)
                writeString(packet, message);
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                // ignore
            }
        }

        public void setEquipment(int slot, String item) {
            if (slot >= 0 && slot < equipment.length) {
                equipment[slot] = item;
            }
        }

        public void setInventoryItem(int slot, String item) {
            if (slot >= 0 && slot < inventory.length) {
                inventory[slot] = item;
            }
        }

        private void sendPlayerPositionAndLook() {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                writeVarInt(packet, 0x08); // Player Position And Look (clientbound)
                packet.writeDouble(x);
                packet.writeDouble(y);
                packet.writeDouble(z);
                packet.writeFloat(yaw);
                packet.writeFloat(pitch);
                packet.writeByte(0); // flags
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                // ignore
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
                if (reason.length() > 50) reason = reason.substring(0, 50);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream packet = new DataOutputStream(buffer);
                writeVarInt(packet, 0x40); // Disconnect (clientbound)
                writeString(packet, reason);
                sendPacket(buffer.toByteArray());
            } catch (IOException e) {
                // ignore
            } finally {
                try { socket.close(); } catch (IOException e) { }
            }
        }
    }

    // ==================== 插件接口 ====================
    public interface Plugin {
        void onEnable();
        void onDisable();
        String getName();
        String getVersion();
    }

    // ==================== 命令执行器接口 ====================
    @FunctionalInterface
    public interface CommandExecutor {
        void onCommand(MCPlayer player, String[] args);
    }

    // ==================== 服务器API接口 ====================
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

    // ==================== 服务器API实现 ====================
    private static class ServerAPIImpl implements ServerAPI {
        @Override
        public void registerCommand(String command, CommandExecutor executor) {
            commands.put(command.toLowerCase(), executor);
            logger.info("[API] 注册命令: /" + command);
        }

        @Override
        public void broadcastMessage(String message) {
            if (message.length() > 100) message = message.substring(0, 100);
            final String msg = message;
            onlinePlayers.values().forEach(player -> player.sendMessage(msg));
            logger.info("[广播] " + msg);
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

    // ==================== 插件类加载器 ====================
    private static class PluginClassLoader extends URLClassLoader {
        public PluginClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }
    }

    // ==================== 插件配置类 ====================
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

    // ==================== 主方法 ====================
    public static void main(String[] args) {
        printBanner();
        loadConfig();
        loadWorld();
        serverAPI = new ServerAPIImpl();
        loadPlugins("plugins");
        enableAllPlugins();
        startMCServer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) stop();
        }));

        // KeepAlive 任务
        scheduler.scheduleAtFixedRate(() -> {
            onlinePlayers.values().forEach(player -> {
                try {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    DataOutputStream packet = new DataOutputStream(buffer);
                    writeVarInt(packet, 0x00); // Keep Alive (clientbound)
                    packet.writeInt(0); // random ID
                    player.sendPacket(buffer.toByteArray());
                } catch (IOException e) {
                    // ignore
                }
            });
        }, 5, 5, TimeUnit.SECONDS);

        try (Scanner scanner = new Scanner(System.in, "UTF-8")) {
            while (running && scanner.hasNextLine()) {
                System.out.print("> ");
                System.out.flush();
                String input = scanner.nextLine().trim();
                if (!input.isEmpty()) handleConsoleCommand(input);
            }
        } catch (Exception e) {
            logger.severe("控制台输入错误: " + e.getMessage());
        }
    }

    // ==================== 加载配置 ====================
    private static void loadConfig() {
        File configFile = new File("server.properties");
        if (!configFile.exists()) {
            logger.info("创建默认配置文件: server.properties");
            try (FileOutputStream output = new FileOutputStream(configFile)) {
                Properties defaultConfig = new Properties();
                defaultConfig.setProperty("server-name", "Mintropy");
                defaultConfig.setProperty("server-port", "25565");
                defaultConfig.setProperty("max-players", "100");
                defaultConfig.setProperty("motd", "Mintropy Server");
                defaultConfig.setProperty("view-distance", "8");
                defaultConfig.setProperty("world-name", "MintropyWorld");
                defaultConfig.store(output, "Mintropy Server Configuration");
            } catch (IOException e) {
                logger.warning("创建配置文件失败: " + e.getMessage());
            }
            return;
        }

        try (FileInputStream input = new FileInputStream(configFile)) {
            serverConfig.load(input);
            SERVER_NAME = serverConfig.getProperty("server-name", "Mintropy");
            PORT = Integer.parseInt(serverConfig.getProperty("server-port", "25565"));
            MAX_PLAYERS = Integer.parseInt(serverConfig.getProperty("max-players", "100"));
            MOTD = serverConfig.getProperty("motd", "Mintropy Server");
            if (MOTD.length() > 48) MOTD = MOTD.substring(0, 48);
            VIEW_DISTANCE = Integer.parseInt(serverConfig.getProperty("view-distance", "8"));
            logger.info("配置文件加载完成");
            logger.info("服务器名称: " + SERVER_NAME);
            logger.info("端口: " + PORT);
            logger.info("最大玩家数: " + MAX_PLAYERS);
        } catch (IOException e) {
            logger.warning("读取配置文件失败: " + e.getMessage());
        }
    }

    // ==================== 加载世界 ====================
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
        logger.info("预生成出生点区块...");
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getChunk(dx, dz);
            }
        }
        logger.info("世界加载完成");
    }

    // ==================== 启动MC服务器 ====================
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
                        if (running) logger.warning("接受连接失败: " + e.getMessage());
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

    // ==================== 处理客户端连接 ====================
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
                    socket.close();
                    return;
                }
                int protocolVersion = readVarInt(packet);
                String serverAddress = readString(packet, 255);
                int serverPort = packet.readUnsignedShort();
                int nextState = readVarInt(packet);

                if (nextState == 1) {
                    handleStatusRequest(input, output);
                } else if (nextState == 2) {
                    handleLoginRequest(socket, input, output);
                }
            } catch (Exception e) {
                try { socket.close(); } catch (IOException ex) { }
            }
        });
        clientThread.setDaemon(true);
        clientThread.start();
    }

    // ==================== 处理状态请求 ====================
    private static void handleStatusRequest(DataInputStream input, DataOutputStream output) throws IOException {
        // 读取请求包 (0x00)
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);

        String safeMotd = MOTD.length() > 48 ? MOTD.substring(0, 48) : MOTD;
        String statusJson = "{\"version\":{\"name\":\"" + MC_VERSION + "\",\"protocol\":" + PROTOCOL_VERSION + "}," +
                           "\"players\":{\"max\":" + MAX_PLAYERS + ",\"online\":" + onlinePlayers.size() + "}," +
                           "\"description\":{\"text\":\"" + safeMotd + "\"}}";

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x00); // Status Response
        writeString(packet, statusJson);
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();

        // 等待Ping (0x01)
        packetLength = readVarInt(input);
        packetData = new byte[packetLength];
        input.readFully(packetData);
        buffer = new ByteArrayOutputStream();
        packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x01); // Pong
        packet.write(packetData); // echo back
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 处理登录请求 ====================
    private static void handleLoginRequest(Socket socket, DataInputStream input, DataOutputStream output) throws IOException {
        // 读取 Login Start (0x00)
        int packetLength = readVarInt(input);
        byte[] packetData = new byte[packetLength];
        input.readFully(packetData);
        DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetData));
        int packetId = readVarInt(packet);
        if (packetId != 0x00) {
            socket.close();
            return;
        }
        String username = readString(packet, 16);
        String playerUUID = "00000000-0000-0000-0000-000000000001";

        // 发送 Login Success (0x02)
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream response = new DataOutputStream(buffer);
        writeVarInt(response, 0x02);
        writeString(response, playerUUID);
        writeString(response, username);
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();

        // 1.8.8 不需要等待客户端确认，直接发送游戏加入包
        MCPlayer player = new MCPlayer(username, playerUUID, socket);
        onlinePlayers.put(username, player);

        // 发送 Join Game (0x01)
        sendJoinGame(output);

        // 发送 Spawn Position (0x05)
        sendSpawnPosition(output);

        // 发送 Player Position And Look (0x08)
        sendPlayerPositionAndLook(output);

        // 发送 Player Abilities (0x39)
        sendPlayerAbilities(output);

        // 发送空区块 (0x21) 以让客户端加载世界
        sendEmptyChunk(output);

        logger.info("玩家 " + username + " 已加入游戏！");
        handleGamePackets(player);
    }

    // ==================== 发送 Join Game (0x01) ====================
    private static void sendJoinGame(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x01);
        packet.writeInt(0); // Entity ID
        packet.writeByte(1); // Gamemode (creative)
        packet.writeByte(0); // Dimension (overworld)
        packet.writeByte(0); // Difficulty (peaceful)
        packet.writeByte(MAX_PLAYERS); // Max players
        writeString(packet, "flat"); // Level type
        packet.writeBoolean(false); // Reduced debug info
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 发送 Spawn Position (0x05) ====================
    private static void sendSpawnPosition(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x05);
        packet.writeInt(0); // X
        packet.writeInt(64); // Y
        packet.writeInt(0); // Z
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 发送 Player Position And Look (0x08) ====================
    private static void sendPlayerPositionAndLook(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x08);
        packet.writeDouble(0); // X
        packet.writeDouble(64); // Y
        packet.writeDouble(0); // Z
        packet.writeFloat(0); // Yaw
        packet.writeFloat(0); // Pitch
        packet.writeByte(0); // Flags
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 发送 Player Abilities (0x39) ====================
    private static void sendPlayerAbilities(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x39);
        packet.writeByte(0x0F); // Flags: invulnerable, flying, allow flying, creative mode
        packet.writeFloat(0.05f); // Flying speed
        packet.writeFloat(0.1f); // Field of view
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 发送空区块 (0x21) ====================
    private static void sendEmptyChunk(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0x21); // Chunk Data
        packet.writeInt(0); // Chunk X
        packet.writeInt(0); // Chunk Z
        packet.writeBoolean(true); // Ground-Up Continuous
        packet.writeShort(0); // Primary Bit Mask (0 = no sections)
        // Data: 256 bytes of biome data (all plains)
        byte[] biomeData = new byte[256];
        Arrays.fill(biomeData, (byte) 1); // 1 = plains
        writeVarInt(packet, biomeData.length);
        packet.write(biomeData);
        writeVarInt(output, buffer.size());
        output.write(buffer.toByteArray());
        output.flush();
    }

    // ==================== 处理游戏内数据包 ====================
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
                    case 0x00: // Keep Alive (serverbound)
                        // Ignore
                        break;
                    case 0x01: // Chat Message (serverbound)
                        String message = readString(packet, 100);
                        handlePlayerChat(player, message);
                        break;
                    case 0x04: // Player Position
                        player.x = packet.readDouble();
                        player.y = packet.readDouble();
                        player.z = packet.readDouble();
                        player.onGround = packet.readBoolean();
                        break;
                    case 0x05: // Player Look
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        player.onGround = packet.readBoolean();
                        break;
                    case 0x06: // Player Position And Look
                        player.x = packet.readDouble();
                        player.y = packet.readDouble();
                        player.z = packet.readDouble();
                        player.yaw = packet.readFloat();
                        player.pitch = packet.readFloat();
                        player.onGround = packet.readBoolean();
                        break;
                    case 0x07: // Player Digging
                        // 忽略方块操作
                        break;
                    case 0x08: // Player Block Placement
                        // 忽略
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            onlinePlayers.remove(player.getUsername());
            logger.info("玩家 " + player.getUsername() + " 断开连接");
        }
    }

    // ==================== 处理玩家聊天 ====================
    private static void handlePlayerChat(MCPlayer player, String message) {
        if (message.startsWith("/")) {
            handlePlayerCommand(player, message.substring(1));
        } else {
            String formattedMessage = "§7<§f" + player.getUsername() + "§7> §f" + message;
            serverAPI.broadcastMessage(formattedMessage);
            logger.info("[聊天] " + player.getUsername() + ": " + message);
        }
    }

    // ==================== 处理玩家命令 ====================
    private static void handlePlayerCommand(MCPlayer player, String commandLine) {
        String[] parts = commandLine.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        CommandExecutor executor = commands.get(command);
        if (executor != null) {
            try {
                executor.onCommand(player, args);
            } catch (Exception e) {
                player.sendMessage("§c命令执行错误");
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
                    commands.keySet().forEach(cmd -> player.sendMessage("§f/" + cmd));
                }
                player.sendMessage("§e==========================");
                break;
            default:
                player.sendMessage("§c未知命令: /" + command);
        }
    }

    // ==================== 处理控制台命令 ====================
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
                break;
            default:
                logger.info("未知命令: " + command);
        }
    }

    // ==================== 列出在线玩家 ====================
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

    // ==================== 插件加载相关 ====================
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

    private static PluginConfig readPluginConfig(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;
            try (InputStream input = jar.getInputStream(entry)) {
                Properties props = new Properties();
                props.load(input);
                String name = props.getProperty("name");
                String version = props.getProperty("version");
                String main = props.getProperty("main");
                if (name == null || version == null || main == null) return null;
                return new PluginConfig(name, version, main);
            }
        } catch (IOException e) {
            return null;
        }
    }

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
                    } catch (Exception e) { }
                });
        } catch (Exception e) { }
    }

    private static void enableAllPlugins() {
        plugins.forEach((name, plugin) -> {
            try {
                plugin.onEnable();
                logger.info("✓ 启用插件: " + plugin.getName());
            } catch (Exception e) {
                logger.severe("✗ 启用插件失败: " + plugin.getName());
            }
        });
    }

    private static void disableAllPlugins() {
        plugins.forEach((name, plugin) -> {
            try {
                plugin.onDisable();
            } catch (Exception e) { }
        });
        plugins.clear();
    }

    private static void reloadPlugins() {
        disableAllPlugins();
        plugins.clear();
        commands.clear();
        loadPlugins("plugins");
        enableAllPlugins();
    }

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

    // ==================== 停止服务器 ====================
    private static void stop() {
        if (!running) return;
        logger.info("正在关闭服务器...");
        running = false;
        disableAllPlugins();
        world.saveAll();
        scheduler.shutdown();
        onlinePlayers.values().forEach(player -> player.disconnect("服务器关闭"));
        onlinePlayers.clear();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) { }
        logger.info("服务器已关闭");
    }

    // ==================== 工具方法 ====================
    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        int position = 0;
        byte currentByte;
        do {
            currentByte = input.readByte();
            result |= (currentByte & 0x7F) << position;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        } while ((currentByte & 0x80) != 0);
        return result;
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            byte temp = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) temp |= 0x80;
            output.writeByte(temp);
        } while (value != 0);
    }

    private static String readString(DataInputStream input, int maxLength) throws IOException {
        int length = readVarInt(input);
        if (length > maxLength) throw new IOException("String too long: " + length + " > " + maxLength);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    // ==================== 打印横幅 ====================
    private static void printBanner() {
        System.out.println("=================================");
        System.out.println("   Mintropy MC Server v" + VERSION);
        System.out.println("   高性能Minecraft服务器");
        System.out.println("   MC版本: " + MC_VERSION);
        System.out.println("=================================");
        System.out.flush();
    }

    // ==================== 显示帮助 ====================
    private static void showHelp() {
        System.out.println("\n========== 控制台命令 ==========");
        System.out.println("  stop       - 停止服务器");
        System.out.println("  plugins    - 列出所有插件");
        System.out.println("  reload     - 重新加载插件");
        System.out.println("  players    - 列出在线玩家");
        System.out.println("  broadcast  - 广播消息");
        System.out.println("  save       - 保存世界");
        System.out.println("  version    - 显示版本信息");
        System.out.println("  help       - 显示此帮助");
        System.out.println("================================\n");
        System.out.flush();
    }
}
