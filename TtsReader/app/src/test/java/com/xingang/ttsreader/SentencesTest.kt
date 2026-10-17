package com.xingang.ttsreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 分句规则：按行、按句末标点切分，常见缩写不切
class SentencesTest {

    @Test
    fun splitsLinesAndSentences() {
        val text = """
            The quick brown fox jumps over the lazy dog. It was a sunny day!
            Dr. Smith said "Hello." Then he left.

            Is this the end? Yes.
        """.trimIndent()
        val s = Sentences.split(text).map { it.text }
        s.forEach { println(it) }
        assertEquals(
            listOf(
                "The quick brown fox jumps over the lazy dog.",
                "It was a sunny day!",
                "Dr. Smith said \"Hello.\"",
                "Then he left.",
                "Is this the end?",
                "Yes.",
            ),
            s,
        )
    }

    // 带注释的行整行作为一条；朗读顺序是 短语（英）→ 中文释义 → 例句（英）
    @Test
    fun keepsNoteLinesWhole() {
        val s = Sentences.split(
            "The company hit the skids. || hit the skids：走下坡路 || hit the skids\nPlain line."
        )
        assertEquals(2, s.size)
        assertEquals("The company hit the skids.", s[0].text)
        assertEquals("hit the skids：走下坡路", s[0].note)
        assertEquals("hit the skids", s[0].headline)
        val parts = s[0].parts()
        assertEquals(listOf("hit the skids", "走下坡路", "The company hit the skids."), parts.map { it.text })
        assertEquals(listOf("en", "zh", "en"), parts.map { it.locale.language })
        // 普通行只读自身
        assertEquals(null, s[1].note)
        assertEquals(listOf("Plain line."), s[1].parts().map { it.text })
        assertTrue(s[1].headline == "Plain line.")
    }

    // 朗读中文时去掉词性标记，屏幕上的注释保持原样
    @Test
    fun stripsPartsOfSpeechWhenSpeaking() {
        val s = Sentences.split(
            "He brought disgrace on his family. || disgrace：n. 耻辱；v. 使丢脸 || disgrace"
        )
        assertEquals("disgrace：n. 耻辱；v. 使丢脸", s[0].note)
        assertEquals(
            listOf("disgrace", "耻辱；使丢脸", "He brought disgrace on his family."),
            s[0].parts().map { it.text },
        )
        assertEquals("短促尖叫", stripPartsOfSpeech("n./v. 短促尖叫"))
        assertEquals("尽管", stripPartsOfSpeech("prep. 尽管"))
        assertEquals("主权的；君主", stripPartsOfSpeech("adj. 主权的；n. 君主"))
        assertEquals("绿松石（色）", stripPartsOfSpeech("n./adj. 绿松石（色）"))
        // 没有词性标记的短语释义原样保留
        assertEquals("否则，要不然", stripPartsOfSpeech("否则，要不然"))
    }

    // 词性标记夹在中间、没有分号隔开时也要去掉，并把两个义项分开
    @Test
    fun stripsPartsOfSpeechInTheMiddle() {
        assertEquals("每年的；年刊", stripPartsOfSpeech("adj. 每年的 n. 年刊"))
        assertEquals("挖洞；洞穴", stripPartsOfSpeech("v. 挖洞 n. 洞穴"))
        assertEquals("档案馆；存档", stripPartsOfSpeech("n. 档案馆 v. 存档"))
        assertEquals("总计；合计的", stripPartsOfSpeech("n. 总计 adj. 合计的"))
        // 已经用分号隔开的照旧
        assertEquals("耻辱；使丢脸", stripPartsOfSpeech("n. 耻辱；v. 使丢脸"))
        // 中文里的句号不受影响
        assertEquals("名词解释", stripPartsOfSpeech("名词解释"))
    }

    // 四种朗读方式各自朗读哪些段
    @Test
    fun speakModesPickTheRightParts() {
        val s = Sentences.split(
            "He brought disgrace on his family. || disgrace：n. 耻辱；v. 使丢脸 || disgrace"
        )[0]
        fun texts(m: SpeakMode) = s.parts(m).map { it.text }
        fun langs(m: SpeakMode) = s.parts(m).map { it.locale.language }

        assertEquals(
            listOf("disgrace", "耻辱；使丢脸", "He brought disgrace on his family."),
            texts(SpeakMode.FULL),
        )
        assertEquals(listOf("disgrace"), texts(SpeakMode.WORD_ONLY))
        assertEquals(listOf("disgrace", "耻辱；使丢脸"), texts(SpeakMode.WORD_THEN_CN))
        assertEquals(listOf("耻辱；使丢脸", "disgrace"), texts(SpeakMode.CN_THEN_WORD))
        assertEquals(listOf("zh", "en"), langs(SpeakMode.CN_THEN_WORD))
        // 原始行保留下来，便于整条移入已掌握 / 未掌握
        assertEquals("disgrace", s.key)
        assertTrue(s.raw.contains(Sentences.NOTE_SEPARATOR))
    }

    // 普通粘贴文本没有单词和释义，任何模式都只读原文
    @Test
    fun plainTextIgnoresModes() {
        val s = Sentences.split("Just a plain sentence here.")[0]
        for (m in SpeakMode.entries) {
            assertEquals(listOf("Just a plain sentence here."), s.parts(m).map { it.text })
        }
    }
}
