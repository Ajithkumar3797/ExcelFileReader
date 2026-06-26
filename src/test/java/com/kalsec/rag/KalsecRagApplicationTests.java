package com.kalsec.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "openai.api-key=test-key",
    "chroma.base-url=http://localhost:8000"
})
class KalsecRagApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts without errors
    }
}
