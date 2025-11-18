package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange

class NextWordAction : WordNavigationAction() {

    override fun findWord(editor: Editor, word: String, currentWordRange: TextRange): Int {
        val document = editor.document
        val text = document.charsSequence
        val startOffset = currentWordRange.endOffset
        
        // Search from startOffset to end
        var index = text.indexOf(word, startOffset, ignoreCase = false)
        while (index != -1) {
            if (isWholeWord(text, index, word.length)) {
                return index
            }
            index = text.indexOf(word, index + 1, ignoreCase = false)
        }
        
        // Wrap around: search from 0 to startOffset
        index = text.indexOf(word, 0, ignoreCase = false)
        val limit = currentWordRange.startOffset
        
        while (index != -1 && index <= limit) {
             if (isWholeWord(text, index, word.length)) {
                return index
            }
            index = text.indexOf(word, index + 1, ignoreCase = false)
        }
        
        return -1
    }
}

