package com.zxce.ai.util.docker

enum class ContainerStatus {
    Created,
    Restarting,
    Running,
    Paused,
    Exited;

    companion object {
        operator fun get(name: String): ContainerStatus {
            val chars = name.toCharArray()
            chars[0] = chars[0].uppercaseChar()
            return ContainerStatus.valueOf(chars.concatToString())
        }
    }
}