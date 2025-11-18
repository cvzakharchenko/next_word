package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange

class PreviousWordAction : WordNavigationAction() {

    override fun findWord(editor: Editor, word: String, currentWordRange: TextRange): Int {
        val document = editor.document
        val text = document.charsSequence
        // Start searching before the current word
        val startOffset = currentWordRange.startOffset - 1
        
        if (startOffset >= 0) {
            var index = text.lastIndexOf(word, startOffset, ignoreCase = false)
            while (index != -1) {
                if (isWholeWord(text, index, word.length)) {
                    return index
                }
                if (index == 0) break 
                index = text.lastIndexOf(word, index - 1, ignoreCase = false)
            }
        }
        
        // Wrap around: search from end
        var index = text.lastIndexOf(word, text.length, ignoreCase = false)
        val limit = currentWordRange.startOffset
        
        while (index != -1 && index >= limit) {
             if (isWholeWord(text, index, word.length)) {
                return index
            }
            if (index == 0) break
            index = text.lastIndexOf(word, index - 1, ignoreCase = false)
        }
        
        return -1
    }
}

