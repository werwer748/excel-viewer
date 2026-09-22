package dev.hugo.sheetview.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * 구문 강조에 쓸 텍스트 파일 타입을 이름으로 찾는다.
 *
 * 이름으로 찾는 이유: `HtmlFileType`/`XmlFileType` 을 직접 참조하면 그 클래스를 싣지 않는
 * IDE(예: 순수 플랫폼 기반 제품)에서 `NoClassDefFoundError` 가 난다. 이 플러그인은
 * `com.intellij.modules.platform` 만 의존하는 한 빌드로 모든 JetBrains IDE 에 설치된다.
 *
 * 폴백이 `PlainText` 인 이유: [com.intellij.openapi.editor.highlighter.EditorHighlighterFactory]
 * 에 **바이너리** 타입을 넘기면 하이라이터를 만들 수 없다. 구문 강조를 못 얻는 것은 감수하지만
 * 원본 탭이 예외로 죽는 것은 감수할 수 없다. 그래서 미등록·바이너리·null 은 전부 PlainText 다.
 */
object TextFileTypes {

    fun byName(name: String?): FileType {
        if (name.isNullOrBlank()) return PlainTextFileType.INSTANCE
        val found = runCatching { FileTypeRegistry.getInstance().findFileTypeByName(name) }.getOrNull()
        return if (found == null || found.isBinary) PlainTextFileType.INSTANCE else found
    }
}
