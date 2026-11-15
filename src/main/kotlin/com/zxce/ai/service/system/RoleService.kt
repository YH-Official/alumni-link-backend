package com.zxce.ai.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zxce.ai.model.system.SysRoleEntity
import com.zxce.ai.model.system.query.QSysRoleEntity
import com.zxce.ai.model.system.query.QSysUserRolesEntity
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class RoleService {

    @Autowired
    lateinit var mapper: JsonMapper

    /**
     * 查询所有角色列表
     *
     * @param ctx 上下文，包含用户信息和数据库操作
     * @return 角色实体列表
     */
    suspend fun list(ctx: Context): List<SysRoleEntity?> {
        return ctx.query<QSysRoleEntity>().toAsync().findList()
    }

    /**
     * 分页查询角色列表，支持按角色名称模糊搜索
     *
     * @param ctx 上下文
     * @param pageNo 页码（从1开始）
     * @param pageSize 每页显示条数
     * @param roleName 角色名称搜索关键字，为null时不进行过滤
     * @return 包含total和rows的Bundle对象
     */
    suspend fun queryRoleByPage(ctx: Context, pageNo: Int, pageSize: Int, roleName: String?): Bundle {
        val roles = ctx.query<QSysRoleEntity> {
            if (!roleName.isNullOrEmpty()) {
                or {
                    this.roleName.contains(roleName)
                    this.type.contains(roleName)
                }
            }
            setMaxRows(pageSize)
            setFirstRow((pageNo - 1) * pageSize)
        }.toAsync().findPagedList()

        // 查询菜单的相关代码已被弃用，此处仅保留用于兼容的代码。
        val data = roles.list.map {
            mapper.toBundle(it).scope {
                "menuId" set emptyList<Int>()
                "menuTitle" set emptyList<String>()
            }
        }

        return bundleScope {
            "total" set roles.totalCount
            "rows" set data
        }
    }

    /**
     * 创建新角色
     *
     * @param ctx 上下文
     * @param bundle 角色数据Bundle，必须包含roleName和type字段
     * @return 创建成功的角色实体
     * @throws IllegalArgumentException 当roleName或type为空时抛出
     */
    suspend fun create(ctx: Context, bundle: Bundle): SysRoleEntity {
        val model = ctx.createModel<SysRoleEntity> {
            roleName = bundle("roleName") ?: throw IllegalArgumentException("必须填写roleName")
            type = bundle("type") ?: throw IllegalArgumentException("必须填写type")
            createdBy = ctx.user.id
        }
        withContext(Dispatchers.IO) {
            model.insert()
            model.refresh()
        }

        return model
    }

    /**
     * 更新角色信息
     *
     * @param ctx 上下文
     * @param bundle 包含角色更新数据的Bundle，需包含角色ID
     * @return 成功响应
     */
    suspend fun update(ctx: Context, bundle: Bundle): Any {
        val model = mapper.toModel<SysRoleEntity>(bundle).apply {
            applyDatabase(ctx)
            updatedBy = ctx.user.id
        }
        withContext(Dispatchers.IO) {
            model.update()
        }
        return success()
    }

    /**
     * 删除指定ID的角色
     *
     * @param ctx 上下文
     * @param id 角色ID
     * @return 成功响应
     * @throws IllegalStateException 当角色为超级管理员或正在被用户使用时抛出
     */
    suspend fun destroy(ctx: Context, id: Int): Any {
        val role = ctx.query<QSysRoleEntity> {
            this.id.eq(id)
        }.toAsync().findOne() ?: return success()
        // 不允许删除超级管理员角色
        if (role.type == "super_admin") throw IllegalStateException("不能删除超级管理员角色")
        // 检查角色是否正在被使用
        ctx.query<QSysUserRolesEntity> {
            this.roleId.eq(role.id)
            if (toAsync().exists()) throw IllegalStateException("该角色正在使用")
        }
        withContext(Dispatchers.IO) {
            role.delete()
        }

        return success()
    }
}