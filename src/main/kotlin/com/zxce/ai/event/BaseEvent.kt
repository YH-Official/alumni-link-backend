package com.zxce.ai.event

import com.zxce.ai.util.bundle.bundleOf
import com.zxce.ai.util.event.Event

open class BaseEvent(source: Any) : Event(source) {
    val attached = bundleOf()
}