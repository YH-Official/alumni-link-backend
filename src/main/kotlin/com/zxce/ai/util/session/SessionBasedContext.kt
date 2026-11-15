package com.zxce.ai.util.session

import com.zxce.ai.manager.SessionManager
import com.zxce.ai.util.bundle.Bundle
import org.springframework.context.ApplicationContext
import java.util.*

open class SessionBasedContext(
    override val applicationContext: ApplicationContext,
    override val sessionId: UUID
) : Context() {
    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }
    private val mSession by lazy { sessionManager[this.sessionId] }
    override val session: SessionMap
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}