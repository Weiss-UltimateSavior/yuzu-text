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
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

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

    private String adminUsername;
    private String adminPassword;

    private String currentToken = null;
    private long tokenExpiry = 0;

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
    }

    @PostConstruct
    void loadAdminConfig() {
        File file = getAdminConfigFile();
        if (file.exists()) {
            try {
                Map<String, String> cfg = mapper.readValue(file, Map.class);
                adminUsername = cfg.get("username");
                adminPassword = cfg.get("password");
                log.info("Admin credentials loaded from admin.json");
            } catch (Exception e) {
                log.warn("Failed to load admin.json, using defaults");
                adminUsername = defaultUsername;
                adminPassword = defaultPassword;
            }
        } else {
            adminUsername = defaultUsername;
            adminPassword = defaultPassword;
        }
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
            cfg.put("password", adminPassword);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, cfg);
            log.info("Admin credentials saved to admin.json");
        } catch (Exception e) {
            log.error("Failed to save admin.json", e);
        }
    }

    public String login(String username, String password) {
        if (!adminUsername.equals(username) || !adminPassword.equals(password)) {
            return null;
        }
        currentToken = UUID.randomUUID().toString().replace("-", "");
        tokenExpiry = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        return currentToken;
    }

    public boolean validateToken(String token) {
        if (token == null || currentToken == null) return false;
        if (System.currentTimeMillis() > tokenExpiry) {
            currentToken = null;
            return false;
        }
        return currentToken.equals(token);
    }

    public Map<String, Object> getPlayerStats() {
        long total = sessionRepo.count();
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        long today = sessionRepo.countSince(todayStart);
        Instant monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        long thisMonth = sessionRepo.countSince(monthStart);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPlayers", total);
        stats.put("todayPlayers", today);
        stats.put("thisMonthPlayers", thisMonth);
        return stats;
    }

    public Map<String, Object> getFeedbackList(int page, int size) {
        List<Feedback> all = feedbackRepo.findAllByOrderByCreatedAtDesc();
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<Feedback> pageData = all.subList(from, to);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Feedback f : pageData) {
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
        String dir = dataLoader.getDataDir();
        if (dir == null || dir.isEmpty()) {
            dir = "src/main/resources/data";
        }
        File file = new File(dir, filename);
        if (!file.exists()) return null;
        try {
            return java.nio.file.Files.readString(file.toPath());
        } catch (Exception e) {
            log.error("Failed to read data file: {}", filename, e);
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
        String dir = dataLoader.getDataDir();
        if (dir == null || dir.isEmpty()) {
            dir = "src/main/resources/data";
        }
        File file = new File(dir, filename);
        try {
            java.nio.file.Files.writeString(file.toPath(), content);
            log.info("Data file updated: {}", filename);
            return true;
        } catch (Exception e) {
            log.error("Failed to write data file: {}", filename, e);
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
        adminPassword = password;
        saveAdminConfig();
        currentToken = null;
        tokenExpiry = 0;
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

    public void restart() {
        log.info("Game restart requested via admin API");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
                context.close();
                SpringApplication.run(YuzuGameApplication.class);
                log.info("Game restarted successfully");
            } catch (Exception e) {
                log.error("Failed to restart", e);
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

            File target = new File(folder, name);
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
