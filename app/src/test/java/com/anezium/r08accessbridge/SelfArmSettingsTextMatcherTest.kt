package com.anezium.r08accessbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSettingsTextMatcherTest {
    @Test
    fun normalizePreservesCyrillicText() {
        assertEquals(
            "номер сборки",
            SelfArmSettingsTextMatcher.normalize("Номер сборки"),
        )
        // NFD folding maps й -> и; screen text and needles must fold identically.
        assertEquals(
            SelfArmSettingsTextMatcher.normalize("ПОДКЛЮЧЕНИЕ УСТРОЙСТВА С ПОМОЩЬЮ КОДА ПОДКЛЮЧЕНИЯ"),
            SelfArmSettingsTextMatcher.normalize("подключение устройства с помощью кода подключения"),
        )
    }

    @Test
    fun containsAnyMatchesRussianSettingsLabels() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Отладка по Wi-Fi",
                "отладка по wi-fi",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Использовать отладку по Wi\u2011Fi",
                "использовать отладку по wi-fi",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Подключение устройства с помощью кода подключения",
                "кода подключения",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Код подключения по Wi‑Fi",
                "код подключения",
            ),
        )
    }

    @Test
    fun containsAnyMatchesKoreanSettingsLabels() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "무선 디버깅",
                "무선 디버깅",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Wi-Fi 페어링 코드",
                "wi-fi 페어링 코드",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "개발자 옵션",
                "개발자 옵션",
            ),
        )
    }

    @Test
    fun containsAnyStillAsciiFoldsLatinAccents() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Utiliser le debogage sans fil",
                "débogage sans fil",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Options pour les développeurs",
                "options pour les developpeurs",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Débogage sans fil",
                "debogage sans fil",
            ),
        )
    }

    @Test
    fun containsBuildIdentifierMatchesLocaleIndependentBuildText() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "SKQ1.240613.001 release-keys",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "Номер сборки SKQ1.240613.001 release-keys",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
        assertFalse(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "Номер модели RKG-123",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
    }
}
