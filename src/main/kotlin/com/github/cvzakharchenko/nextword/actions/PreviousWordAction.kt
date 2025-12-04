package com.github.cvzakharchenko.nextword.actions

import com.intellij.openapi.util.TextRange

class PreviousWordAction : WordNavigationAction() {

    override fun findTargetOccurrence(occurrences: List<Int>, currentWordRange: TextRange): Int {
        if (occurrences.isEmpty()) return -1
        
        val startOffset = currentWordRange.startOffset
        
        // Find the last occurrence before currentWordRange.startOffset
        for (i in occurrences.indices.reversed()) {
            val offset = occurrences[i]
            if (offset < startOffset) {
                return offset
            }
        }
        
        // Wrap around: return the last occurrence (after current position)
        // But skip if it's the same as current word
        for (i in occurrences.indices.reversed()) {
            val offset = occurrences[i]
            if (offset > currentWordRange.startOffset) {
                return offset
            }
        }
        
        // If only one occurrence and it's the current word, return -1
        return -1
    }
}
