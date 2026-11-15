package com.zxce.ai.util.dependency

import com.zxce.ai.util.dependency.IContainerProcessComponent.Companion.__equals
import com.zxce.ai.util.docker.IContainer

open class ContainerProcessComponent(
    container: () -> IContainer,
    override val name: String,
    override val description: String = "",
    pid: () -> Int? = { null },
    spawn: suspend () -> Unit = {}
) : IContainerProcessComponent {

    constructor(
        component: IContainerComponent,
        name: String,
        description: String = "",
        pid: () -> Int? = { null },
        spawn: suspend () -> Unit = {}
    ) : this({ component.container }, name, description, pid, spawn)

    constructor(
        container: IContainer,
        name: String,
        description: String = "",
        pid: () -> Int? = { null },
        spawn: suspend () -> Unit = {}
    ) : this({ container }, name, description, pid, spawn)

    private val containerGetter = container
    private val pidGetter = pid
    private val spawnBlock = spawn

    override val container get() = containerGetter()
    override val pid get() = pidGetter()
    override suspend fun spawn() = spawnBlock()
    override fun equals(other: Any?) = __equals(other)
    override fun hashCode() = pid ?: 0
}