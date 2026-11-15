package com.zxce.ai.event.system

import com.zxce.ai.model.system.AuthorizeUser

class UserLoginEvent(source: Any, val data: AuthorizeUser, val sessionId: String) : SystemEvent(source)
class UserLogoutEvent(source: Any, val session: Map<String, Any?>, val sessionId: String) : SystemEvent(source)