package io.github.mangi.eta.ui.voice

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider

internal data class SpeechVoicePreset(
    val id: String,
    @param:StringRes val name: Int,
    @param:StringRes val summary: Int,
)

internal object SpeechVoicePresets {
    private val qwen = listOf(
        SpeechVoicePreset("Cherry", R.string.speech_voice_qwen_cherry, R.string.speech_voice_female),
        SpeechVoicePreset("Serena", R.string.speech_voice_qwen_serena, R.string.speech_voice_female),
        SpeechVoicePreset("Ethan", R.string.speech_voice_qwen_ethan, R.string.speech_voice_male),
        SpeechVoicePreset("Chelsie", R.string.speech_voice_qwen_chelsie, R.string.speech_voice_female),
        SpeechVoicePreset("Momo", R.string.speech_voice_qwen_momo, R.string.speech_voice_female),
        SpeechVoicePreset("Vivian", R.string.speech_voice_qwen_vivian, R.string.speech_voice_female),
        SpeechVoicePreset("Moon", R.string.speech_voice_qwen_moon, R.string.speech_voice_male),
        SpeechVoicePreset("Maia", R.string.speech_voice_qwen_maia, R.string.speech_voice_female),
        SpeechVoicePreset("Kai", R.string.speech_voice_qwen_kai, R.string.speech_voice_male),
        SpeechVoicePreset("Nofish", R.string.speech_voice_qwen_nofish, R.string.speech_voice_male),
        SpeechVoicePreset("Bella", R.string.speech_voice_qwen_bella, R.string.speech_voice_female),
        SpeechVoicePreset("Katerina", R.string.speech_voice_qwen_katerina, R.string.speech_voice_female),
        SpeechVoicePreset("Vincent", R.string.speech_voice_qwen_vincent, R.string.speech_voice_male),
        SpeechVoicePreset("Neil", R.string.speech_voice_qwen_neil, R.string.speech_voice_male),
        SpeechVoicePreset("Dylan", R.string.speech_voice_qwen_dylan, R.string.speech_voice_beijing_male),
        SpeechVoicePreset("Jada", R.string.speech_voice_qwen_jada, R.string.speech_voice_shanghai_female),
    )

    private val doubao = listOf(
        SpeechVoicePreset("zh_female_vv_uranus_bigtts", R.string.speech_voice_doubao_vv, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_xiaohe_uranus_bigtts", R.string.speech_voice_doubao_xiaohe, R.string.speech_voice_female),
        SpeechVoicePreset("zh_male_m191_uranus_bigtts", R.string.speech_voice_doubao_yunzhou, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_taocheng_uranus_bigtts", R.string.speech_voice_doubao_xiaotian, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_liufei_uranus_bigtts", R.string.speech_voice_doubao_liufei, R.string.speech_voice_male),
        SpeechVoicePreset("zh_female_qingxinnvsheng_uranus_bigtts", R.string.speech_voice_doubao_qingxin, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_cancan_uranus_bigtts", R.string.speech_voice_doubao_cancan, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_tianmeixiaoyuan_uranus_bigtts", R.string.speech_voice_doubao_xiaoyuan, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_tianmeitaozi_uranus_bigtts", R.string.speech_voice_doubao_taozi, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_shuangkuaisisi_uranus_bigtts", R.string.speech_voice_doubao_sisi, R.string.speech_voice_female),
        SpeechVoicePreset("zh_female_linjianvhai_uranus_bigtts", R.string.speech_voice_doubao_linjia, R.string.speech_voice_female),
        SpeechVoicePreset("zh_male_shaonianzixin_uranus_bigtts", R.string.speech_voice_doubao_zixin, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_wennuanahu_uranus_bigtts", R.string.speech_voice_doubao_ahu, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_ruyayichen_uranus_bigtts", R.string.speech_voice_doubao_yichen, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_jieshuoxiaoming_uranus_bigtts", R.string.speech_voice_doubao_xiaoming, R.string.speech_voice_male),
        SpeechVoicePreset("zh_male_shenyeboke_uranus_bigtts", R.string.speech_voice_doubao_podcast, R.string.speech_voice_male),
    )

    private val gemini = listOf(
        SpeechVoicePreset("Kore", R.string.speech_voice_gemini_kore, R.string.speech_voice_firm),
        SpeechVoicePreset("Puck", R.string.speech_voice_gemini_puck, R.string.speech_voice_upbeat),
        SpeechVoicePreset("Charon", R.string.speech_voice_gemini_charon, R.string.speech_voice_informative),
        SpeechVoicePreset("Aoede", R.string.speech_voice_gemini_aoede, R.string.speech_voice_breezy),
        SpeechVoicePreset("Zephyr", R.string.speech_voice_gemini_zephyr, R.string.speech_voice_bright),
        SpeechVoicePreset("Leda", R.string.speech_voice_gemini_leda, R.string.speech_voice_youthful),
        SpeechVoicePreset("Sulafat", R.string.speech_voice_gemini_sulafat, R.string.speech_voice_warm),
        SpeechVoicePreset("Achernar", R.string.speech_voice_gemini_achernar, R.string.speech_voice_soft),
    )

    private val minimax = listOf(
        SpeechVoicePreset("female-chengshu", R.string.speech_voice_minimax_mature, R.string.speech_voice_female),
        SpeechVoicePreset("female-tianmei", R.string.speech_voice_minimax_sweet, R.string.speech_voice_female),
        SpeechVoicePreset("female-shaonv", R.string.speech_voice_minimax_girl, R.string.speech_voice_female),
        SpeechVoicePreset("female-yujie", R.string.speech_voice_minimax_yujie, R.string.speech_voice_female),
        SpeechVoicePreset("male-qn-qingse", R.string.speech_voice_minimax_young, R.string.speech_voice_male),
        SpeechVoicePreset("male-qn-jingying", R.string.speech_voice_minimax_elite, R.string.speech_voice_male),
        SpeechVoicePreset("Chinese (Mandarin)_Reliable_Executive", R.string.speech_voice_minimax_executive, R.string.speech_voice_male),
        SpeechVoicePreset("Chinese (Mandarin)_News_Anchor", R.string.speech_voice_minimax_news, R.string.speech_voice_female),
    )

    fun forProvider(provider: TtsProvider): List<SpeechVoicePreset> = when (provider) {
        TtsProvider.QWEN -> qwen
        TtsProvider.DOUBAO -> doubao
        TtsProvider.GEMINI -> gemini
        TtsProvider.MINIMAX -> minimax
        TtsProvider.NONE -> emptyList()
    }
}
