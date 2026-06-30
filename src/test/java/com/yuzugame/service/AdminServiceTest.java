package com.yuzugame.service;

import com.yuzugame.engine.GameDataLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AdminServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void importData_acceptsValidJsonZip() throws Exception {
        AdminService service = newService();
        byte[] zip = zipOf("items.json", "[]");

        int count = service.importData(zip);

        assertEquals(1, count);
        assertEquals("[]", Files.readString(tempDir.resolve("items.json")));
        service.shutdown();
    }

    @Test
    void importData_rejectsTooManyJsonFiles() throws Exception {
        AdminService service = newService();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i <= AdminService.MAX_IMPORT_FILES; i++) {
                zos.putNextEntry(new ZipEntry("file" + i + ".json"));
                zos.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.importData(baos.toByteArray()));

        assertEquals("Import contains too many JSON files", e.getMessage());
        service.shutdown();
    }

    @Test
    void importData_rejectsOversizedJsonEntry() throws Exception {
        AdminService service = newService();
        StringBuilder content = new StringBuilder();
        content.append("{\"data\":\"");
        content.append("x".repeat((int) AdminService.MAX_IMPORT_FILE_BYTES + 1));
        content.append("\"}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.importData(zipOf("large.json", content.toString())));

        assertEquals("Imported JSON file is too large: large.json", e.getMessage());
        service.shutdown();
    }

    private AdminService newService() {
        GameDataLoader dataLoader = new GameDataLoader();
        ReflectionTestUtils.setField(dataLoader, "dataDir", tempDir.toString());
        return new AdminService(
                null,
                null,
                dataLoader,
                null,
                null,
                null,
                null
        );
    }

    private static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
