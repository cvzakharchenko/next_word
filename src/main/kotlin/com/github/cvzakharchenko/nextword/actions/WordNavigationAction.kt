package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Result of word search containing found offset and occurrence info
 */
data class SearchResult(
    val foundOffset: Int,
    val currentIndex: Int,
    val totalCount: Int
)

abstract class WordNavigationAction : AnAction(), DumbAware {

    companion object {
        // Track the current inlay hint across all instances
        private var currentInlay: Inlay<*>? = null
        // Use Application as parent disposable for proper lifecycle management
        private val alarm: Alarm by lazy {
            Alarm(Alarm.ThreadToUse.SWING_THREAD, ApplicationManager.getApplication())
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // Perform document reads within a ReadAction to properly acquire read lock
        val searchResult = ReadAction.compute<SearchResult?, RuntimeException> {
            val targetRange = getTargetWordRange(editor) ?: return@compute null
            val targetWord = editor.document.getText(targetRange)

            if (targetWord.isEmpty()) return@compute null

            // Combined search and count in a single operation
            findWordWithOccurrences(editor, targetWord, targetRange)
        } ?: return

        if (searchResult.foundOffset != -1) {
            val targetWord = ReadAction.compute<String, RuntimeException> {
                val targetRange = getTargetWordRange(editor) ?: return@compute ""
                editor.document.getText(targetRange)
            }
            
            if (targetWord.isEmpty()) return

            val caretModel = editor.caretModel
            val selectionModel = editor.selectionModel

            // Move caret to the end of the found word
            caretModel.moveToOffset(searchResult.foundOffset + targetWord.length)

            // Select the word
            selectionModel.setSelection(searchResult.foundOffset, searchResult.foundOffset + targetWord.length)

            // Scroll to make it visible
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

            // Show inlay hint with occurrence count
            showOccurrenceHint(editor, searchResult.foundOffset, searchResult.currentIndex, searchResult.totalCount)
        }
    }

    private fun getTargetWordRange(editor: Editor): TextRange? {
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            return TextRange(selectionModel.selectionStart, selectionModel.selectionEnd)
        }

        // If no selection, get word at caret
        val caretOffset = editor.caretModel.offset
        val document = editor.document
        val text = document.charsSequence

        if (text.isEmpty()) return null

        // Clamp caretOffset
        val offset = caretOffset.coerceIn(0, text.length)

        // Find word boundaries around caret
        var start = offset
        var end = offset

        // Adjust start if we are at the end of a word/text
        // If at end of text or current char is not identifier part, check previous
        if (start > 0 && (start == text.length || !Character.isJavaIdentifierPart(text[start]))) {
            if (Character.isJavaIdentifierPart(text[start - 1])) {
                start--
            }
        }

        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) {
            start--
        }

        // Reset end to start to scan forward
        end = start
        while (end < text.length && Character.isJavaIdentifierPart(text[end])) {
            end++
        }

        if (start < end) {
            return TextRange(start, end)
        }
        return null
    }

    /**
     * Finds the target word and counts all occurrences in a single pass.
     * Returns SearchResult with the found offset and occurrence statistics.
     */
    private fun findWordWithOccurrences(editor: Editor, word: String, currentWordRange: TextRange): SearchResult {
        val text = editor.document.charsSequence
        
        // First, collect all whole-word occurrences in a single pass
        val occurrences = mutableListOf<Int>()
        var index = text.indexOf(word, 0, ignoreCase = false)
        while (index != -1) {
            if (isWholeWord(text, index, word.length)) {
                occurrences.add(index)
            }
            index = text.indexOf(word, index + 1, ignoreCase = false)
        }
        
        if (occurrences.isEmpty()) {
            return SearchResult(-1, 0, 0)
        }
        
        // Find the target occurrence based on direction
        val foundOffset = findTargetOccurrence(occurrences, currentWordRange)
        
        // Determine the current index (1-based)
        val currentIndex = if (foundOffset != -1) {
            occurrences.indexOf(foundOffset) + 1
        } else {
            0
        }
        
        return SearchResult(foundOffset, currentIndex, occurrences.size)
    }

    /**
     * Given all occurrences, find the target occurrence based on navigation direction.
     * To be implemented by subclasses.
     */
    protected abstract fun findTargetOccurrence(occurrences: List<Int>, currentWordRange: TextRange): Int

    protected fun isWholeWord(text: CharSequence, offset: Int, length: Int): Boolean {
        if (offset < 0 || offset + length > text.length) return false

        val startChar = text[offset]
        val endChar = text[offset + length - 1]

        // Check boundary before
        if (Character.isJavaIdentifierPart(startChar)) {
            if (offset > 0 && Character.isJavaIdentifierPart(text[offset - 1])) {
                return false
            }
        }

        // Check boundary after
        if (Character.isJavaIdentifierPart(endChar)) {
            if (offset + length < text.length && Character.isJavaIdentifierPart(text[offset + length])) {
                return false
            }
        }

        return true
    }

    private fun showOccurrenceHint(editor: Editor, offset: Int, currentIndex: Int, totalCount: Int) {
        // Clean up previous hint
        alarm.cancelAllRequests()
        currentInlay?.let {
            if (it.isValid) {
                Disposer.dispose(it)
            }
        }
        currentInlay = null

        // Create new hint
        val hintText = " $currentIndex/$totalCount "

        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val fontMetrics = editor.contentComponent.getFontMetrics(
                    editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                )
                return fontMetrics.stringWidth(hintText) + 10 // Padding
            }

            override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
                val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                g.font = font
                g.color = JBColor.GRAY // Theme-aware color

                val x = targetRegion.x + 5
                val y = targetRegion.y + editor.ascent
                g.drawString(hintText, x, y)
            }
        }

        val inlay = editor.inlayModel.addAfterLineEndElement(offset, false, renderer)
        currentInlay = inlay

        // Schedule removal after 2 seconds
        if (inlay != null) {
            alarm.addRequest({
                if (inlay.isValid && currentInlay == inlay) {
                    Disposer.dispose(inlay)
                    currentInlay = null
                }
            }, 2000)
        }
    }
}
