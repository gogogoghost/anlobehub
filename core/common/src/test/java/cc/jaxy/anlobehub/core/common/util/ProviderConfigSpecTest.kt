package cc.jaxy.anlobehub.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigSpecTest {
    @Test
    fun `provider forms resolve endpoint and authentication behavior`() {
        val openAi = providerConfigSpec("openai", "builtin")
        assertEquals("https://api.openai.com/v1", openAi.endpoint?.placeholder)
        assertTrue(openAi.showApiKey)

        val ollama = providerConfigSpec("ollama", "builtin")
        assertEquals("http://127.0.0.1:11434", ollama.endpoint?.placeholder)
        assertFalse(ollama.showApiKey)

        val oauth = providerConfigSpec("githubcopilot", "builtin")
        assertTrue(oauth.isOAuth)
        assertFalse(oauth.showApiKey)

        assertTrue(providerConfigSpec("my-proxy", "custom").showApiKey)
    }

    @Test
    fun `provider-specific credential modes identify secret fields`() {
        val bedrock = requireNotNull(specialFieldsFor("bedrock"))
        assertEquals("region", bedrock.apiKeyItems.single().key)
        assertEquals(FieldType.SELECT, bedrock.apiKeyItems.single().type)
        val bedrockSecrets = bedrock.authModes.flatMap { it.fields }
            .filter { it.type == FieldType.SECRET }
            .map { it.key }
            .toSet()
        assertEquals(setOf(VAULT_API_KEY, VAULT_SECRET_ACCESS_KEY, VAULT_SESSION_TOKEN), bedrockSecrets)

        val comfy = requireNotNull(specialFieldsFor("comfyui"))
        val basicAuthSecrets = comfy.authModes.single { it.id == "basic" }.fields
            .filter { it.type == FieldType.SECRET }
            .map { it.key }
        assertEquals(listOf(VAULT_PASSWORD), basicAuthSecrets)
    }
}
