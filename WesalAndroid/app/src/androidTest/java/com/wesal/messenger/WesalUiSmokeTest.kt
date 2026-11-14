package com.wesal.messenger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.core.theme.WesalTheme
import androidx.compose.material3.Text
import org.junit.Rule
import org.junit.Test

class WesalUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun brandTheme_displaysArabicTitleAndEgyptCode() {
        composeRule.setContent {
            WesalTheme(darkTheme = true) {
                Text("وصال ${CountryCatalog.defaultEgypt.dialCode}")
            }
        }
        composeRule.onNodeWithText("وصال +20").assertIsDisplayed()
    }
}
