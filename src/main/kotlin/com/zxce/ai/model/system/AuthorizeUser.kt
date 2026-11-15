package com.zxce.ai.model.system

import com.fasterxml.jackson.annotation.JsonAlias
import java.util.UUID

class AuthorizeUser {
    var id: UUID? = null
    var userName: String = ""
    var realName: String? = ""
    var password: String? = null
    var phone: String? = null
    var email: String? = null
    var avatar: String? = null
    var isUse = true

    @JsonAlias("Authorization")
    var authorization: String? = null
}