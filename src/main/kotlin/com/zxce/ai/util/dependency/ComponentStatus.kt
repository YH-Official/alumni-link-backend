package com.zxce.ai.util.dependency

import com.fasterxml.jackson.annotation.JsonValue

enum class ComponentStatus(val value: String) {
    Running("running"),
    Exited("exited"),
    NotExist("not-exist"),
    HalfUp("half-up"),
    Starting("starting");

    @JsonValue
    override fun toString(): String {
        return value
    }
}