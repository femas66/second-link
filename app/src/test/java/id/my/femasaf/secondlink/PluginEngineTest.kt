package id.my.femasaf.secondlink

import org.junit.Assert.*
import org.junit.Test

class PluginEngineTest {

    @Test
    fun parseUserExample1_backupHome() {
        val yaml = """
            name: backup-home

            trigger:
              type: wifi_connected
              ssid: Rumah

            conditions:
              battery_above: 30

            actions:
              - sync:
                  source: /storage/emulated/0/DCIM
                  destination: nas://photos

              - telegram:
                  message: "Backup selesai"
        """.trimIndent()

        val result = PluginParser.parse(yaml)
        assertTrue("Parsing backup-home should succeed", result.isSuccess)
        val plugin = result.getOrThrow()
        assertEquals("backup-home", plugin.name)
        assertTrue(plugin.trigger is PluginTrigger.WifiConnected)
        assertEquals("Rumah", (plugin.trigger as PluginTrigger.WifiConnected).ssid)
        assertEquals(1, plugin.conditions.size)
        assertTrue(plugin.conditions.first() is PluginCondition.BatteryAbove)
        assertEquals(30, (plugin.conditions.first() as PluginCondition.BatteryAbove).percent)
        assertEquals(2, plugin.actions.size)
        assertTrue(plugin.actions[0] is PluginAction.Sync)
        assertTrue(plugin.actions[1] is PluginAction.Telegram)
    }

    @Test
    fun parseUserExample2_serverWatch() {
        val yaml = """
            name: server-watch

            trigger:
              every: 1m

            conditions:
              - http_unreachable:
                  url: http://localhost:8000

            actions:
              - command: "./server restart"
              - telegram:
                  message: "Server mati dan sudah direstart."
        """.trimIndent()

        val result = PluginParser.parse(yaml)
        assertTrue("Parsing server-watch should succeed", result.isSuccess)
        val plugin = result.getOrThrow()
        assertEquals("server-watch", plugin.name)
        assertTrue(plugin.trigger is PluginTrigger.Interval)
        assertEquals(60_000L, (plugin.trigger as PluginTrigger.Interval).intervalMs)
        assertEquals(1, plugin.conditions.size)
        assertTrue(plugin.conditions.first() is PluginCondition.HttpUnreachable)
        assertEquals(2, plugin.actions.size)
        assertTrue(plugin.actions[0] is PluginAction.Command)
        assertTrue(plugin.actions[1] is PluginAction.Telegram)
    }

    @Test
    fun parseCatalogTemplates_allValid() {
        for (template in PluginCatalog.templates) {
            val result = PluginParser.parse(template.yamlContent)
            assertTrue("Template ${template.id} should parse successfully: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val parsed = result.getOrThrow()
            assertFalse(parsed.name.isBlank())
            assertTrue(parsed.actions.isNotEmpty())
        }
    }

    @Test
    fun parseInvalidYaml_returnsFailureGracefully() {
        val badYaml = """
            name:
            - missing: [unclosed
        """.trimIndent()

        val result = PluginParser.parse(badYaml)
        assertTrue("Invalid YAML must fail gracefully", result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun parseMissingName_fails() {
        val noName = """
            trigger:
              every: 5m
            actions:
              - telegram:
                  message: "test"
        """.trimIndent()

        val result = PluginParser.parse(noName)
        assertTrue("Plugin without name must fail", result.isFailure)
    }

    @Test
    fun parseMissingActions_fails() {
        val noActions = """
            name: test-plugin
            trigger:
              every: 5m
        """.trimIndent()

        val result = PluginParser.parse(noActions)
        assertTrue("Plugin without actions must fail", result.isFailure)
    }

    @Test
    fun telegramKeyboard_includesPluginScreensAndCallbacks() {
        val callback = TelegramKeyboard.callback("plugin", "list")
        assertEquals("v1:plugin:list", callback)

        val mainMarkup = TelegramKeyboard.main()
        val hasPluginButton = mainMarkup.inlineKeyboard.flatten().any { it.callbackData?.contains("plugin") == true }
        assertTrue("Main menu should contain Plugin button", hasPluginButton)

        val pluginMarkup = TelegramKeyboard.screen(MenuScreen.PLUGINS)
        assertTrue("Plugins menu should contain actions", pluginMarkup.inlineKeyboard.isNotEmpty())
    }
}
