package com.sirrlevi.guardian.parent

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.Delivery
import com.sirrlevi.guardian.core.DeliveryState
import com.sirrlevi.guardian.core.ReportCategory
import com.sirrlevi.guardian.core.TelegramSettings
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.TelegramScreen
import com.sirrlevi.guardian.parent.ui.TelegramUiTexts
import com.sirrlevi.guardian.parent.ui.deliveryPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Telegram screen has to say what is true: what is saved, what Telegram confirmed and what was not, and what is never sent.
 * These tests render and click only; they never type, because text input is what the earlier Compose hang was about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class TelegramScreenTest {
    @get:Rule val rule = createComposeRule()

    private val token = "123456789" + ":" + "AAEhBOweik6ad9r_QXMENQjcrOaazbPvyYo" // assembled in code: a made-up token, kept out of the source as one literal
    private val now = System.currentTimeMillis()
    private val backend = FakeTelegramBackend()

    private fun delivery(key: String, state: DeliveryState, error: String? = null, messageId: Long = 0L, category: ReportCategory = ReportCategory.SAFETY) =
        Delivery(key, category, "Guardian · Aarav's phone\nReview recommended\n\nTitle $key", now - 60_000L, state = state, lastError = error, messageId = messageId, sentAt = if (state == DeliveryState.SENT) now - 30_000L else 0L)

    /**
     * Shows the screen with this state. When [connected] is given, the fake storage matches it so clicks act on the real model; no typing, no background threads.
     */
    private fun show(
        enabled: Boolean = false, credentials: Boolean = false, connection: ConnectionStatus? = null, items: List<Delivery> = emptyList(),
        categories: Set<ReportCategory> = setOf(ReportCategory.SAFETY, ReportCategory.DEVICE_STATUS)
    ): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.telegram.io = EmptyCoroutineContext
        val settings = TelegramSettings(enabled = enabled, categories = categories, reportFrom = now - 3_600_000L)
        backend.settingsNow = settings
        backend.outboxNow = items
        backend.connectionNow = connection
        backend.creds = if (credentials) TelegramCredentials(token, "555000111") else null
        vm.telegramBackendForTest = backend
        vm.telegram.showForTest(settings = settings, hasCredentials = credentials, chatId = if (credentials) "555000111" else "", connection = connection, items = items)
        rule.setContent { GuardianTheme(ThemeMode.Light) { TelegramScreen(vm, PaddingValues(0.dp)) } }
        rule.waitForIdle()
        return vm
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun beforeSetupItSaysNoTokenIsSavedAndNothingHasBeenReported() {
        show()
        assertTrue(has(TelegramScreenTexts.INTRO_TITLE))
        assertTrue(has(TelegramScreenTexts.NO_TOKEN))
        assertTrue(has(TelegramScreenTexts.NEED_CREDENTIALS))
        assertTrue(has(TelegramUiTexts.NOTHING))
        assertFalse("nothing to send or retry yet", has(TelegramUiTexts.SEND_NOW) || has(TelegramUiTexts.RETRY_ALL))
    }

    @Test fun theScreenStatesWhatIsNeverSentAndWhatTheKeystoreCannotProtect() {
        show()
        assertTrue(has(TelegramScreenTexts.NOT_OFFERED_TITLE))
        assertTrue(has(TelegramScreenTexts.NOT_OFFERED))
        assertTrue(has(TelegramScreenTexts.LIMITS_TITLE))
        assertTrue(has(TelegramScreenTexts.LIMITS))
        assertTrue(TelegramScreenTexts.LIMITS.contains("not end-to-end encrypted"))
        assertTrue(TelegramScreenTexts.NOT_OFFERED.contains("can name an app"))
    }

    @Test fun aSavedTokenIsNeverShownAgainOnlyThatOneIsSaved() {
        show(credentials = true)
        assertTrue(has(TelegramScreenTexts.HAS_TOKEN))
        assertTrue(has(TelegramScreenTexts.NOT_TESTED))
        assertFalse(has(token, substring = true))
        assertTrue(has(TelegramUiTexts.REMOVE))
        assertTrue(has(TelegramUiTexts.REPLACE))
    }

    @Test fun aWorkingConnectionShowsWhatTelegramConfirmed() {
        show(credentials = true, connection = ConnectionStatus(now, true, "Connected to @guardian_test_bot. Telegram delivered a test message (message 5).", "guardian_test_bot"))
        assertTrue(has("Connected", substring = false))
        assertTrue(has("Telegram delivered a test message", substring = true))
    }

    @Test fun aFailedConnectionIsShownAsNotConnected() {
        show(credentials = true, connection = ConnectionStatus(now, false, "The bot token was rejected."))
        assertTrue(has("Not connected"))
        assertTrue(has("The bot token was rejected."))
    }

    @Test fun turningReportsOnWithoutATestedConnectionIsRefusedOnScreen() {
        val vm = show(credentials = true)
        rule.onNodeWithText(TelegramUiTexts.ENABLE).performClick()
        rule.waitForIdle()
        assertTrue(has(TelegramScreenTexts.TEST_FIRST))
        assertFalse(vm.telegram.settings.enabled)
        assertFalse(backend.settingsNow.enabled)
    }

    @Test fun testConnectionSendsARealTestMessageAndOnlyThenShowsConnected() {
        val vm = show(credentials = true)
        rule.onNodeWithText(TelegramUiTexts.TEST).performClick()
        rule.waitForIdle()
        assertEquals(listOf("getMe", "sendMessage"), backend.scripted.urls.map { it.substringAfterLast('/') })
        assertTrue(has("Connected"))
        assertTrue(vm.telegram.connection?.ok == true)
    }

    @Test fun aRejectedTestShowsNotConnectedAndNeverConnected() {
        backend.scripted.answers.add(ScriptedTransport.fail(401, "Unauthorized"))
        val vm = show(credentials = true)
        rule.onNodeWithText(TelegramUiTexts.TEST).performClick()
        rule.waitForIdle()
        assertTrue(has("Not connected"))
        assertFalse(has("Connected"))
        assertFalse(vm.telegram.connection?.ok == true)
    }

    @Test fun deliveriesShowWhatTelegramConfirmedAndWhatNeedsALook() {
        show(
            enabled = true, credentials = true, connection = ConnectionStatus(now, true, "Connected"),
            items = listOf(
                delivery("sent1", DeliveryState.SENT, messageId = 42),
                delivery("wait1", DeliveryState.WAITING),
                delivery("fail1", DeliveryState.FAILED, error = "Telegram says the chat was not found."),
                delivery("unc1", DeliveryState.UNCONFIRMED)
            )
        )
        assertTrue(has("1 sent by Telegram · 1 waiting · 2 need a look"))
        assertTrue(has("Telegram message 42"))
        assertTrue(has("Telegram says the chat was not found."))
        assertTrue(has(TelegramUiTexts.UNCONFIRMED_NOTE))
        assertTrue(has(TelegramUiTexts.RETRY))
        assertTrue(has(TelegramUiTexts.SEND_AGAIN))
        assertTrue(has(TelegramUiTexts.SEND_NOW))
        assertTrue(has(TelegramUiTexts.RETRY_ALL))
        assertTrue(has(TelegramUiTexts.CLEAR))
    }

    @Test fun aReportWithoutAMessageIdIsNeverShownAsSent() {
        show(enabled = true, credentials = true, connection = ConnectionStatus(now, true, "Connected"), items = listOf(delivery("odd", DeliveryState.UNCONFIRMED)))
        assertTrue(has("0 sent by Telegram", substring = true))
        assertFalse(has("Telegram message", substring = true))
    }

    @Test fun retryingAFailedReportIsSentAgainAndShownAsSentOnlyAfterTelegramConfirms() {
        val vm = show(enabled = true, credentials = true, connection = ConnectionStatus(now, true, "Connected"), items = listOf(delivery("fail1", DeliveryState.FAILED, error = "Telegram is busy.")))
        backend.scripted.answers.add(ScriptedTransport.fail(500, "Internal Server Error"))
        rule.onNodeWithText(TelegramUiTexts.RETRY).performClick()
        rule.waitForIdle()
        assertEquals(1, backend.scripted.sends)
        assertEquals(DeliveryState.RETRYING, vm.telegram.items.single().state)
        assertFalse(has("Telegram message", substring = true))
        assertTrue(has("Will try again"))
    }

    @Test fun theCategoriesAndRetentionChoicesAreShownWithWhatEachSends() {
        show(credentials = true)
        ReportCategory.values().forEach { assertTrue(it.title, has(it.title)); assertTrue(it.about, has(it.about)) }
        listOf("1 day", "7 days", "30 days", "90 days").forEach { assertTrue(it, has(it)) }
    }

    @Test fun theDeliveryListShowsTheLabelAndTitleNotJustTheDeviceLine() {
        assertEquals("Review recommended · Title x", deliveryPreview("Guardian · Aarav's phone\nReview recommended\n\nTitle x\nReason y"))
    }
}
