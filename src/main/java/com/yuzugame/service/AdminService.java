package com.yuzugame.service;

import com.yuzugame.YuzuGameApplication;
import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.Feedback;
import com.yuzugame.repository.FeedbackRepository;
import com.yuzugame.repository.GameSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final GameSessionRepository sessionRepo;
    private final FeedbackRepository feedbackRepo;
    private final GameDataLoader dataLoader;
    private final LlmService llmService;
    private final AuditLlmService auditLlmService;
    private final InputAuditor inputAuditor;
    private final ConfigurableApplicationContext context;

    @Value("${yuzu.admin.username}")
    private String defaultUsername;

    @Value("${yuzu.admin.password}")
    private String defaultPassword;

    private volatile String adminUsername;
    private volatile String adminPasswordHash;

    private final Map<String, Long> activeTokens = new HashMap<>();
    private static final long TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
    private final ScheduledExecutorService tokenCleanupScheduler;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AdminService(GameSessionRepository sessionRepo,
                        FeedbackRepository feedbackRepo,
                        GameDataLoader dataLoader,
                        LlmService llmService,
                        AuditLlmService auditLlmService,
                        InputAuditor inputAuditor,
                        ConfigurableApplicationContext context) {
        this.sessionRepo = sessionRepo;
        this.feedbackRepo = feedbackRepo;
        this.dataLoader = dataLoader;
        this.llmService = llmService;
        this.auditLlmService = auditLlmService;
        this.inputAuditor = inputAuditor;
        this.context = context;
        this.tokenCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "admin-token-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.tokenCleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredTokens,
                TOKEN_TTL_MS, TOKEN_TTL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        tokenCleanupScheduler.shutdown();
    }

    private synchronized void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        activeTokens.entrySet().removeIf(e -> now > e.getValue());
    }

    @PostConstruct
    void loadAdminConfig() {
        File file = getAdminConfigFile();
        if (file.exists()) {
            try {
                Map<String, String> cfg = mapper.readValue(file, Map.class);
                adminUsername = cfg.get("username");
                adminPasswordHash = cfg.get("passwordHash");
                // 兼容旧版 SHA-256 格式：如果存在 salt 字段，说明是旧格式，需要迁移
                String legacySalt = cfg.get("passwordSalt");
                String legacyPassword = cfg.get("password");
                if (adminPasswordHash != null && legacySalt != null) {
                    // 旧格式 SHA-256 哈希，无法直接迁移（不可逆），需要重置
                    log.warn("Detected legacy SHA-256 password hash, resetting to default password. Please update via admin panel.");
                    adminPasswordHash = passwordEncoder.encode(defaultPassword);
                    saveAdminConfig();
                } else if (adminPasswordHash == null && legacyPassword != null) {
                    // 更早的明文密码格式
                    adminPasswordHash = passwordEncoder.encode(legacyPassword);
                    saveAdminConfig();
                    log.info("Migrated legacy plaintext password to BCrypt format");
                }
                log.info("Admin credentials loaded from admin.json");
            } catch (Exception e) {
                log.warn("Failed to load admin.json, using defaults");
                initDefaultCredentials();
            }
        } else {
            initDefaultCredentials();
        }
    }

    private void initDefaultCredentials() {
        adminUsername = defaultUsername;
        adminPasswordHash = passwordEncoder.encode(defaultPassword);
    }

    private File getAdminConfigFile() {
        String dir = dataLoader.getDataDir();
        if (dir == null || dir.isEmpty()) dir = "src/main/resources/data";
        return new File(dir, "admin.json");
    }

    private void saveAdminConfig() {
        try {
            File file = getAdminConfigFile();
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("username", adminUsername);
            cfg.put("passwordHash", adminPasswordHash);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, cfg);
            log.info("Admin credentials saved to admin.json");
        } catch (Exception e) {
            log.error("Failed to save admin.json", e);
        }
    }

    public synchronized String login(String username, String password) {
        if (username == null || password == null) return null;
        if (adminUsername == null || adminPasswordHash == null) return null;
        if (!adminUsername.equals(username) || !passwordEncoder.matches(password, adminPasswordHash)) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    public synchronized boolean validateToken(String token) {
        if (token == null) return false;
        Long expiry = activeTokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            activeTokens.remove(token);
            return false;
        }
        return true;
    }

    public Map<String, Object> getPlayerStats() {
        long total = sessionRepo.count();
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        long today = sessionRepo.countSince(todayStart);
        Instant monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        long thisMonth = sessionRepo.countSince(monthStart);
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        long activePlayers = sessionRepo.countActiveSince(oneHourAgo);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPlayers", total);
        stats.put("todayPlayers", today);
        stats.put("thisMonthPlayers", thisMonth);
        stats.put("activePlayers", activePlayers);
        return stats;
    }

    public Map<String, Object> getFeedbackList(int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<Feedback> feedbackPage = feedbackRepo.findAllByOrderByCreatedAtDesc(pageable);
        long total = feedbackPage.getTotalElements();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Feedback f : feedbackPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.getId());
            item.put("contact", f.getContact() != null ? f.getContact() : "");
            item.put("content", f.getContent());
            item.put("sessionId", f.getSessionId() != null ? f.getSessionId() : "");
            item.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : "");
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> getProgressStats() {
        List<Object[]> chapterCounts = sessionRepo.countByChapter();
        Double avgTurns = sessionRepo.avgTurn();

        Map<String, Long> chapterDistribution = new LinkedHashMap<>();
        for (Object[] row : chapterCounts) {
            String chapter = row[0] != null ? row[0].toString() : "unknown";
            Long count = (Long) row[1];
            chapterDistribution.put(chapter, count);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chapterDistribution", chapterDistribution);
        stats.put("averageTurns", avgTurns != null ? Math.round(avgTurns * 100.0) / 100.0 : 0);
        return stats;
    }

    private static final Set<String> PROTECTED_FILES = Set.of("admin.json");

    private static boolean isProtectedFile(String name) {
        return PROTECTED_FILES.contains(name);
    }

    public List<String> listDataFiles() {
        String dir = dataLoader.getDataDir();
        if (dir == null || dir.isEmpty()) {
            dir = "src/main/resources/data";
        }
        File folder = new File(dir);
        if (!folder.exists() || !folder.isDirectory()) {
            return List.of();
        }
        File[] files = folder.listFiles((d, name) -> name.endsWith(".json") && !isProtectedFile(name));
        if (files == null) return List.of();
        List<String> names = new ArrayList<>();
        for (File f : files) names.add(f.getName());
        Collections.sort(names);
        return names;
    }

    public String readDataFile(String filename) {
        if (isProtectedFile(filename)) return null;
        String dir = resolveDataDir();
        try {
            File file = resolveSecureFile(dir, filename);
            if (!file.exists()) return null;
            return java.nio.file.Files.readString(file.toPath());
        } catch (java.io.IOException e) {
            log.error("Path validation failed for file: {} - {}", filename, e.getMessage());
            return null;
        }
    }

    public boolean writeDataFile(String filename, String content) {
        if (isProtectedFile(filename)) {
            log.warn("Rejected write to protected file: {}", filename);
            return false;
        }
        if (!filename.endsWith(".json")) {
            log.warn("Rejected non-json file write: {}", filename);
            return false;
        }
        try {
            mapper.readTree(content);
        } catch (Exception e) {
            log.warn("Invalid JSON content for {}: {}", filename, e.getMessage());
            return false;
        }
        String dir = resolveDataDir();
        try {
            File file = resolveSecureFile(dir, filename);
            java.nio.file.Files.writeString(file.toPath(), content);
            log.info("Data file updated: {}", filename);
            return true;
        } catch (java.io.IOException e) {
            log.error("Failed to write data file: {} - {}", filename, e.getMessage());
            return false;
        }
    }

    public void reloadGameData() {
        dataLoader.reload();
    }

    public Map<String, String> getAdminInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("username", adminUsername);
        return info;
    }

    public boolean updateAdminCredentials(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        adminUsername = username;
        adminPasswordHash = passwordEncoder.encode(password);
        saveAdminConfig();
        activeTokens.clear();
        log.info("Admin credentials updated");
        return true;
    }

    public Map<String, String> getLlmConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseUrl", llmService.getBaseUrl());
        config.put("model", llmService.getModel());
        config.put("apiKey", maskKey(llmService.getApiKey()));
        return config;
    }

    public Map<String, String> getAuditLlmConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseUrl", auditLlmService.getBaseUrl());
        config.put("model", auditLlmService.getModel());
        config.put("apiKey", maskKey(auditLlmService.getApiKey()));
        return config;
    }

    public void updateLlmConfig(String baseUrl, String apiKey, String model) {
        llmService.updateConfig(baseUrl, apiKey, model);
    }

    public void updateAuditLlmConfig(String baseUrl, String apiKey, String model) {
        auditLlmService.updateConfig(baseUrl, apiKey, model);
    }

    public boolean isAuditorEnabled() {
        return inputAuditor.isEnabled();
    }

    public void setAuditorEnabled(boolean enabled) {
        inputAuditor.setEnabled(enabled);
    }

    private volatile boolean restarting = false;

    public synchronized void restart() {
        if (restarting) {
            log.warn("Restart already in progress, ignoring duplicate request");
            return;
        }
        restarting = true;
        log.info("Game restart requested via admin API");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000);
                context.close();
                Thread.sleep(1000);
                SpringApplication.run(YuzuGameApplication.class);
                log.info("Game restarted successfully");
            } catch (Exception e) {
                log.error("Failed to restart", e);
            } finally {
                restarting = false;
            }
        });
        t.setDaemon(false);
        t.setName("admin-restart");
        t.start();
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private String resolveDataDir() {
        String dir = dataLoader.getDataDir();
        if (dir == null || dir.isEmpty()) {
            dir = "src/main/resources/data";
        }
        return dir;
    }

    private File resolveSecureFile(String dir, String filename) throws java.io.IOException {
        File baseDir = new File(dir).getCanonicalFile();
        File target = new File(baseDir, filename).getCanonicalFile();
        if (!target.getCanonicalPath().startsWith(baseDir.getCanonicalPath() + File.separator) && !target.getCanonicalPath().equals(baseDir.getCanonicalPath())) {
            throw new java.io.IOException("Path traversal detected: " + filename);
        }
        return target;
    }

    public byte[] exportAllData() throws Exception {
        String dir = resolveDataDir();
        File folder = new File(dir);
        if (!folder.exists() || !folder.isDirectory()) {
            throw new RuntimeException("Data directory not found: " + dir);
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos);

        File[] files = folder.listFiles((d, name) -> name.endsWith(".json") && !isProtectedFile(name));
        if (files != null) {
            for (File f : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                zos.write(java.nio.file.Files.readAllBytes(f.toPath()));
                zos.closeEntry();
            }
        }

        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    public int importData(byte[] zipBytes) throws Exception {
        String dir = resolveDataDir();
        File folder = new File(dir);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
        int count = 0;
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (!name.endsWith(".json")) continue;

            int slashIdx = name.lastIndexOf('/');
            if (slashIdx >= 0) name = name.substring(slashIdx + 1);

            if (isProtectedFile(name)) {
                log.warn("Skipping protected file in import: {}", name);
                continue;
            }

            File target = resolveSecureFile(dir, name);
            java.io.ByteArrayOutputStream fileBaos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = zis.read(buf)) > 0) {
                fileBaos.write(buf, 0, len);
            }
            String fileContent = fileBaos.toString("UTF-8");
            try {
                mapper.readTree(fileContent);
            } catch (Exception e) {
                log.warn("Skipping invalid JSON in import: {} - {}", name, e.getMessage());
                continue;
            }
            java.nio.file.Files.writeString(target.toPath(), fileContent);
            log.info("Imported data file: {}", name);
            count++;
        }
        zis.close();
        return count;
    }
}
