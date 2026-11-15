package com.zxce.ai.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zxce.ai.model.system.SysUserRolesEntity
import com.zxce.ai.model.system.query.QSysRoleEntity
import com.zxce.ai.model.system.query.QSysUserRolesEntity
import com.zxce.ai.model.system.query.QViewUserRoleEntity
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.bundle.toBundle
import com.zxce.ai.util.ebean.createModel
import com.zxce.ai.util.ebean.query
import com.zxce.ai.util.ebean.toModel
import com.zxce.ai.util.ebean.toUuid
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import com.zxce.ai.util.ebean.toAsync

@Service
class UserRoleService {

    @Autowired
    lateinit var mapper: JsonMapper

    /**
     * 创建用户角色关联记录
     *
     * @param context 上下文
     * @param userRole 用户角色数据Bundle，包含userId和roleId等信息
     * @return 成功响应
     */
    suspend fun create(context: Context, userRole: Bundle): Any {
        withContext(Dispatchers.IO) {
            mapper.toModel<SysUserRolesEntity>(userRole).insert()
        }
        return success()
    }

    /**
     * 根据当前登录用户ID查询角色信息
     *
     * @param ctx 上下文，从中获取当前登录用户ID
     * @return 包含角色信息的Bundle，若不存在则返回默认值（roleId=0, userId=0）
     */
    suspend fun getByUserId(ctx: Context, userId: String): Bundle {
        val rs = ctx.query<QViewUserRoleEntity> {
            this.userId.eq(ctx.user.id)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
        return rs?.let { mapper.toBundle(it) } ?: bundleScope {
            "roleId" set 0
            "userId" set 0
        }
    }

    /**
     * 根据用户ID获取关联的角色信息，若用户无角色则自动创建学生角色
     *
     * @param ctx 上下文
     * @param user 包含userId的Bundle对象
     * @return 包含角色信息的Bundle，额外包含roleType字段
     */
    suspend fun getRoleByUserId(ctx: Context, user: Bundle): Bundle = ctx {
        val userId = user["userId"]?.toString()!!.toUuid()
        val has = query<QSysUserRolesEntity>().userId.eq(userId).toAsync().exists()
        // 找不到角色时自动创建学生角色（roleId=4 对应 student）
        if (!has) {
            withContext(Dispatchers.IO){
                createModel<SysUserRolesEntity> {
                    this.userId = userId
                    this.roleId = 4
                }.insert()
            }
        }
        // 查询用户角色视图
        val rs = query<QViewUserRoleEntity> {
            this.userId.eq(userId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
        val out = rs?.let { mapper.toBundle(it) } ?: bundleScope {
            "roleId" set 0
            "userId" set 0
        }
        // 复制type字段为roleType
        out["roleType"] = out["type"]
        return out
    }
}