package com.yuzugame.controller;

import com.yuzugame.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        String clientIp = extractClientIp(request);
        String token = adminService.login(username, password, clientIp);
        Map<String, Object> result = new LinkedHashMap<>();
        if (token != null) {
            result.put("success", true);
            result.put("token", token);
        } else {
            result.put("success", false);
            result.put("message", "Invalid credentials or rate limited");
        }
        return result;
    }

    /** 提取真实客户端 IP，考虑 X-Forwarded-For 头 */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 取第一个 IP（最原始的客户端）
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/stats/players")
    public Map<String, Object> getPlayerStats(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        return adminService.getPlayerStats();
    }

    @GetMapping("/feedbacks")
    public Map<String, Object> getFeedbackList(@RequestHeader("X-Admin-Token") String token,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        requireAuth(token);
        if (size > 100) size = 100;
        if (size < 1) size = 1;
        return adminService.getFeedbackList(page, size);
    }

    @GetMapping("/stats/progress")
    public Map<String, Object> getProgressStats(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        return adminService.getProgressStats();
    }

    @GetMapping("/data/files")
    public Map<String, Object> listDataFiles(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        List<String> files = adminService.listDataFiles();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        return result;
    }

    @GetMapping("/data/file")
    public Map<String, Object> readDataFile(@RequestHeader("X-Admin-Token") String token,
                                            @RequestParam String name) {
        requireAuth(token);
        String content = adminService.readDataFile(name);
        Map<String, Object> result = new LinkedHashMap<>();
        if (content != null) {
            result.put("name", name);
            result.put("content", content);
        } else {
            result.put("error", "File not found: " + name);
        }
        return result;
    }

    @PutMapping("/data/file")
    public Map<String, Object> writeDataFile(@RequestHeader("X-Admin-Token") String token,
                                             @RequestBody Map<String, String> body) {
        requireAuth(token);
        String name = body.get("name");
        String content = body.get("content");
        boolean ok = adminService.writeDataFile(name, content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        if (!ok) {
            result.put("message", "Failed to write file: check filename extension (.json) and content format");
        }
        return result;
    }

    @PostMapping("/data/reload")
    public Map<String, Object> reloadGameData(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        adminService.reloadGameData();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Game data reloaded");
        return result;
    }

    @GetMapping("/data/export")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> exportData(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        try {
            java.io.InputStream stream = adminService.exportAllDataStream();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "game-data.zip");
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new org.springframework.core.io.InputStreamResource(stream));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/data/import")
    public Map<String, Object> importData(@RequestHeader("X-Admin-Token") String token,
                                          @RequestParam("file") MultipartFile file) {
        requireAuth(token);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int count = adminService.importData(file.getBytes());
            result.put("success", true);
            result.put("importedFiles", count);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/llm/config")
    public Map<String, Object> getLlmConfig(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm", adminService.getLlmConfig());
        result.put("auditLlm", adminService.getAuditLlmConfig());
        return result;
    }

    @PutMapping("/llm/config")
    public Map<String, Object> updateLlmConfig(@RequestHeader("X-Admin-Token") String token,
                                               @RequestBody Map<String, String> body) {
        requireAuth(token);
        String type = body.getOrDefault("type", "llm");
        String baseUrl = body.get("baseUrl");
        String apiKey = body.get("apiKey");
        String model = body.get("model");

        if ("audit".equals(type)) {
            adminService.updateAuditLlmConfig(baseUrl, apiKey, model);
        } else {
            adminService.updateLlmConfig(baseUrl, apiKey, model);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/auditor/status")
    public Map<String, Object> getAuditorStatus(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", adminService.isAuditorEnabled());
        return result;
    }

    @PutMapping("/auditor/status")
    public Map<String, Object> setAuditorStatus(@RequestHeader("X-Admin-Token") String token,
                                                 @RequestBody Map<String, Object> body) {
        requireAuth(token);
        Object enabledObj = body.get("enabled");
        if (enabledObj == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Missing 'enabled' field");
            return result;
        }
        boolean enabled = Boolean.parseBoolean(enabledObj.toString());
        adminService.setAuditorEnabled(enabled);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("enabled", enabled);
        return result;
    }

    @PostMapping("/restart")
    public Map<String, Object> restart(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        adminService.restart();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Server is restarting");
        return result;
    }

    @GetMapping("/info")
    public Map<String, Object> getAdminInfo(@RequestHeader("X-Admin-Token") String token) {
        requireAuth(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admin", adminService.getAdminInfo());
        return result;
    }

    @PutMapping("/credentials")
    public Map<String, Object> updateAdminCredentials(@RequestHeader("X-Admin-Token") String token,
                                                       @RequestBody Map<String, String> body) {
        requireAuth(token);
        String username = body.get("username");
        String password = body.get("password");
        boolean ok = adminService.updateAdminCredentials(username, password);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        if (ok) {
            result.put("message", "Credentials updated, please login again");
        } else {
            result.put("message", "Username and password must not be empty");
        }
        return result;
    }

    private void requireAuth(String token) {
        if (!adminService.validateToken(token)) {
            throw new AdminAuthException();
        }
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class AdminAuthException extends RuntimeException {
        AdminAuthException() {
            super("Unauthorized: invalid or expired admin token");
        }
    }
}
