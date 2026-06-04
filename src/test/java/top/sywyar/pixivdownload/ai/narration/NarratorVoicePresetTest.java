package top.sywyar.pixivdownload.ai.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("旁白音色预设注册表")
class NarratorVoicePresetTest {

    @Test
    @DisplayName("默认预设为温暖女声且为清单首项；DEFAULT_NARRATOR_INSTRUCTION 取其画像")
    void defaultIsWarmFemaleAndFirst() {
        assertSame(NarratorVoicePreset.WARM_FEMALE, NarratorVoicePreset.DEFAULT);
        assertSame(NarratorVoicePreset.DEFAULT, NarratorVoicePreset.all().get(0));
        assertEquals(NarratorVoicePreset.DEFAULT.instruction(), NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION);
    }

    @Test
    @DisplayName("每个预设 id 为非空 kebab 且互不重复；画像非空且写到位（含音高 / 音质等维度）")
    void presetsAreWellFormed() {
        Pattern kebab = Pattern.compile("[a-z]+(-[a-z]+)*");
        Set<String> ids = new HashSet<>();
        for (NarratorVoicePreset p : NarratorVoicePreset.all()) {
            assertTrue(kebab.matcher(p.id()).matches(), "id 应为 kebab：" + p.id());
            assertTrue(ids.add(p.id()), "id 不应重复：" + p.id());
            assertFalse(p.instruction() == null || p.instruction().isBlank());
            assertTrue(p.instruction().contains("Pitch"), "画像应限定音高：" + p.id());
            assertTrue(p.instruction().contains("Timbre"), "画像应限定音质：" + p.id());
        }
    }

    @Test
    @DisplayName("byId：命中返回对应预设；空 / 未知返回 null")
    void byIdLookup() {
        assertSame(NarratorVoicePreset.CALM_MALE, NarratorVoicePreset.byId("calm-male"));
        assertNull(NarratorVoicePreset.byId(null));
        assertNull(NarratorVoicePreset.byId(""));
        assertNull(NarratorVoicePreset.byId("nope"));
    }
}
