package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.util.regex.Pattern

abstract class WordNavigationAction : AnAction(), DumbAware {

    companion object {
        // Track the current inlay hint across all instances
        private var currentInlay: Inlay<*>? = null
        private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val targetRange = getTargetWordRange(editor) ?: return
        val targetWord = editor.document.getText(targetRange)
        
        if (targetWord.isEmpty()) return

        val foundOffset = findWord(editor, targetWord, targetRange)
        if (foundOffset != -1) {
            val caretModel = editor.caretModel
            val selectionModel = editor.selectionModel

            // Move caret to the end of the found word
            caretModel.moveToOffset(foundOffset + targetWord.length)
            
            // Select the word
            selectionModel.setSelection(foundOffset, foundOffset + targetWord.length)
            
            // Scroll to make it visible
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            
            // Show inlay hint with occurrence count
            val (currentIndex, totalCount) = countOccurrences(editor, targetWord, foundOffset)
            showOccurrenceHint(editor, foundOffset, targetWord, currentIndex, totalCount)
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
    
    protected abstract fun findWord(editor: Editor, word: String, currentWordRange: TextRange): Int
    
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
    
    private fun countOccurrences(editor: Editor, word: String, currentOffset: Int): Pair<Int, Int> {
        val text = editor.document.charsSequence
        var count = 0
        var currentIndex = 0
        
        var index = text.indexOf(word, 0, ignoreCase = false)
        while (index != -1) {
            if (isWholeWord(text, index, word.length)) {
                count++
                if (index == currentOffset) {
                    currentIndex = count
                }
            }
            index = text.indexOf(word, index + 1, ignoreCase = false)
        }
        
        return Pair(currentIndex, count)
    }
    
    private fun showOccurrenceHint(editor: Editor, offset: Int, word: String, currentIndex: Int, totalCount: Int) {
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
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        
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
                
                val fontMetrics = g.fontMetrics
                val x = targetRegion.x + 5
                val y = targetRegion.y + editor.ascent
                g.drawString(hintText, x, y)
            }
        }
        
        val inlay = editor.inlayModel.addAfterLineEndElement(lineEndOffset, false, renderer)
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
