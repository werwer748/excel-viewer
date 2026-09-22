package dev.hugo.sheetview.editor

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil

/**
 * 원본 탭의 소스·파트 보기에 쓸 **밝은** 에디터 색 구성표를 고른다.
 *
 * 왜 배경색만 바꾸면 안 되는가: 다크 테마의 구문 강조 색(밝은 노랑·연회색)이 흰 바탕에 그대로
 * 얹히면 지금보다 더 안 보인다. 색 구성표 자체를 갈아끼워야 한다.
 *
 * 왜 이름 하나만 믿지 않는가: 번들 구성표 이름은 IDE 버전과 제품마다 다르다. "밝은 것
 * 아무거나"가 특정 이름보다 오래 간다. 회귀 테스트는 `LightEditorSchemeTest`.
 */
object LightEditorScheme {

    fun pick(): EditorColorsScheme {
        val manager = EditorColorsManager.getInstance()
        // getDefaultScheme() 은 @ApiStatus.Internal 이라 쓰지 않는다 (Plugin Verifier 가 잡는다).
        // allSchemes 가 어차피 기본 구성표를 포함하므로 잃는 것이 없다.
        val candidates = listOfNotNull(
            // 있으면 이게 가장 익숙하다. 없으면 아래로 내려간다.
            runCatching { manager.getScheme(PREFERRED) }.getOrNull(),
        ) + runCatching { manager.allSchemes.toList() }.getOrDefault(emptyList())

        return candidates.firstOrNull { !ColorUtil.isDark(it.defaultBackground) }
        // 밝은 구성표가 하나도 없는 IDE 라면 전역을 쓴다. 다크일 수 있지만 **지금과 같아질 뿐**
        // 더 나빠지지는 않는다. 여기서 예외를 던져 원본 탭을 못 열게 만드는 것이 훨씬 나쁘다.
            ?: manager.globalScheme
    }

    private const val PREFERRED = "IntelliJ Light"
}
