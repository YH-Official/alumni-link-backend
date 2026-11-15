package com.zxce.ai.model

import io.ebean.Database

interface ModelCompanion {
    @Deprecated(message = "use spring bean instead.", replaceWith = ReplaceWith("ctx.database", "ctx"))
    val database: Database
}