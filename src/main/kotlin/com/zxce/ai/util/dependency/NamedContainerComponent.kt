package com.zxce.ai.util.dependency

import com.zxce.ai.util.dependency.IContainerComponent.Companion.__equals
import com.zxce.ai.util.docker.IContainer
import kotlinx.coroutines.runBlocking

open class NamedContainerComponent(
    override val name: String,
    override val containerName: String,
    override val description: String = ""
) : IContainerComponent {

    private companion object {
        val manager get() = IComponent.manager
    }

    override val cid get() = container.id

    override val container: IContainer
        get() = mContainer ?: throw NoSuchElementException("容器不存在")

    protected val mContainer: IContainer?
        get() = runBlocking { manager.getContainerByName(containerName) }

    override val exists: Boolean
        get() = mContainer != null

    override fun getContainerOrNull(): IContainer? {
        return mContainer
    }

    override fun equals(other: Any?) = __equals(other)
    override fun hashCode() = cid.hashCode()
}