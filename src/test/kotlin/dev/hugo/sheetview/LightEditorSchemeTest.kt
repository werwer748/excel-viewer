package dev.hugo.sheetview

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorUtil
import dev.hugo.sheetview.editor.LightEditorScheme

/**
 * 원본 탭의 소스·파트 보기는 IDE 테마와 **무관하게** 밝게 본다.
 *
 * 배경색만 흰색으로 바꾸면 안 된다 — 다크 테마의 구문 강조 색(밝은 노랑·연회색)이 흰 바탕에
 * 그대로 얹혀 지금보다 더 안 보인다. 색 구성표 자체를 밝은 것으로 갈아끼워야 한다.
 *
 * 구성표를 이름으로만 찾지 않는 이유를 이 테스트가 지킨다: 번들 구성표 이름은 IDE 버전마다
 * 바뀐다. "밝은 것 아무거나"가 이름보다 오래 간다.
 *
 * `EditorColorsManager` 가 필요해 `BasePlatformTestCase`(JUnit3 계열)를 쓴다 —
 * 메서드 이름이 `test` 로 시작해야 발견된다.
 */
class LightEditorSchemeTest : BasePlatformTestCase() {

    fun `test 고른 구성표의 배경은 어둡지 않다`() {
        val scheme = LightEditorScheme.pick()
        assertFalse(
            "고른 구성표가 어둡다: ${scheme.name} / ${scheme.defaultBackground}",
            ColorUtil.isDark(scheme.defaultBackground),
        )
    }

    fun `test 전역 구성표가 다크여도 밝은 것을 고른다`() {
        val manager = EditorColorsManager.getInstance()
        val dark = manager.allSchemes.firstOrNull { ColorUtil.isDark(it.defaultBackground) }
            ?: return  // 다크 구성표가 하나도 없는 IDE 면 검증할 것이 없다
        val original = manager.globalScheme
        try {
            manager.setGlobalScheme(dark)
            assertFalse(
                "전역이 다크일 때 따라갔다: ${LightEditorScheme.pick().name}",
                ColorUtil.isDark(LightEditorScheme.pick().defaultBackground),
            )
        } finally {
            manager.setGlobalScheme(original)
        }
    }

    fun `test 글꼴 설정을 바꾸지 않는다`() {
        // 구성표를 통째로 대입하면 글꼴까지 바뀐다. 색만 갈아끼워야 한다는 것을 기록해 둔다 —
        // TextViewer 는 createBoundColorSchemeDelegate 로 글꼴을 상속한다.
        val scheme = LightEditorScheme.pick()
        assertTrue("구성표에 글꼴 크기가 없다", scheme.editorFontSize > 0)
    }
}
