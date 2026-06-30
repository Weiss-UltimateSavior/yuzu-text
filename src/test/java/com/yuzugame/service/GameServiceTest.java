package com.yuzugame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.engine.GameEngine;
import com.yuzugame.model.GameSession;
import com.yuzugame.repository.GameSessionRepository;
import com.yuzugame.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    @Test
    void prepareSessionForRedis_skipsSensitiveSessionWhenEncryptionKeyMissing() throws Exception {
        GameService service = newService();
        GameSession session = sessionWithCustomApiKey("plain-secret");

        GameSession redisSession = service.prepareSessionForRedis(session);

        assertNull(redisSession);
    }

    @Test
    void prepareSessionForRedis_encryptsAndDecryptsCustomApiKey() throws Exception {
        GameService service = newService();
        String encryptionKey = CryptoUtils.generateKey();
        ReflectionTestUtils.setField(service, "fieldEncryptionKey", encryptionKey);
        GameSession session = sessionWithCustomApiKey("plain-secret");

        GameSession redisSession = service.prepareSessionForRedis(session);

        assertNotNull(redisSession);
        assertEquals("plain-secret", session.getCustomLlmApiKey());
        assertNotEquals("plain-secret", redisSession.getCustomLlmApiKey());
        assertTrue(CryptoUtils.isEncrypted(redisSession.getCustomLlmApiKey()));

        service.decryptRedisSensitiveFields(redisSession);
        assertEquals("plain-secret", redisSession.getCustomLlmApiKey());
    }

    private static GameService newService() {
        return new GameService(
                (GameEngine) null,
                (GameSessionRepository) null,
                (StringRedisTemplate) null,
                new ObjectMapper(),
                (GameDataLoader) null,
                (LlmService) null
        );
    }

    private static GameSession sessionWithCustomApiKey(String apiKey) {
        GameSession session = new GameSession();
        session.setSessionId("session-1");
        session.setCustomLlmBaseUrl("https://example.com/v1");
        session.setCustomLlmApiKey(apiKey);
        session.setCustomLlmModel("model");
        return session;
    }
}
