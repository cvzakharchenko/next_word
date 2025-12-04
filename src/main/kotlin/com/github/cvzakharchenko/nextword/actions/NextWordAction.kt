package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.util.TextRange

class NextWordAction : WordNavigationAction() {

    override fun findTargetOccurrence(occurrences: List<Int>, currentWordRange: TextRange): Int {
        if (occurrences.isEmpty()) return -1
        
        val startOffset = currentWordRange.endOffset
        
        // Find the first occurrence after currentWordRange.endOffset
        for (offset in occurrences) {
            if (offset >= startOffset) {
                return offset
            }
        }
        
        // Wrap around: return the first occurrence (before current position)
        // But skip if it's the same as current word
        for (offset in occurrences) {
            if (offset < currentWordRange.startOffset) {
                return offset
            }
        }
        
        // If only one occurrence and it's the current word, return -1
        return -1
    }
}
