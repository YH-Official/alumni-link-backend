package com.zxce.ai.util.session

import com.zxce.ai.api.SystemApi
import com.zxce.ai.model.system.SysRoleEntity
import com.zxce.ai.model.system.query.QSysRoleEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.model.system.query.QSysUserRolesEntity
import com.zxce.ai.util.ebean.query
import com.zxce.ai.util.ebean.toUuid
import com.zxce.ai.util.isClient
import kotlinx.coroutines.runBlocking
import java.util.*

class UserInfo(val context: Context) {
    val id: UUID by lazy { context.userId.toUuid() }
    val userEntity by lazy {
        if (isClient) {
            return@lazy runBlocking {
                SystemApi.myUserInfo(context)
            }
        }
        context.query<QSysUserEntity>().id.eq(id).findOne() ?: throw NoSuchElementException("未找到用户 $id")
    }

    private val roleEntity: SysRoleEntity by lazy {
        if (isClient) {
            return@lazy runBlocking {
                val role = SystemApi.myRole(context)
                SysRoleEntity().apply {
                    id = role["roleId"] as Int
                    roleName = role["roleName"] as String
                    type = role["roleType"] as String
                }
            }
        }
        val ae = context.query<QSysUserRolesEntity>().userId.eq(id).findOne()
        ae ?: throw NoSuchElementException("未找到用户角色 $id")
        val role = context.query<QSysRoleEntity>().id.eq(ae.roleId).findOne()
        role ?: throw NoSuchElementException("找不到角色 ${ae.id}")
        return@lazy role
    }

    val userName get() = userEntity.userName
    val realName get() = userEntity.realName
    val phone get() = userEntity.phone
    val isUse get() = userEntity.isUse
    val roleId get() = roleEntity.id
    val roleName get() = roleEntity.roleName
    val roleType get() = roleEntity.type
    val isAdmin get() = "admin" in roleType
    val isStudent get() = roleType == "student"
}