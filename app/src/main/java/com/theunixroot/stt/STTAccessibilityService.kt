package com.theunixroot.stt

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class STTAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to track focus if needed
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: STTAccessibilityService? = null

        fun injectTextIntoFocusedInput(text: String): Boolean {
            val service = instance ?: return false
            val rootNode = service.rootInActiveWindow ?: return false

            // Try to find the focused editable node
            val targetNode = findFocusedEditableNode(rootNode)
            if (targetNode != null) {
                val currentText = targetNode.text?.toString() ?: ""
                val newText = if (currentText.isBlank()) text else "$currentText $text"
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        newText
                    )
                }
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                targetNode.recycle()
                rootNode.recycle()
                return success
            }
            rootNode.recycle()
            return false
        }

        private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // First check input focus
            val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && (focused.isEditable || focused.className?.contains("EditText", ignoreCase = true) == true)) {
                return focused
            }

            // Fallback: depth-first search for focused and editable node
            if (node.isFocused && (node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true)) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findFocusedEditableNode(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
            return null
        }
    }
}
