package com.zxce.ai.controller.system

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.model.system.SysRoleEntity
import com.zxce.ai.service.system.RoleService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 角色管理控制器
 *
 * 提供角色管理的RESTful API，包括角色的查询、创建、更新和删除等功能。
 * 所有接口路径前缀为 /api/role，需要用户登录认证。
 */
@HostForward
@RestController
@RequestMapping("/api/role")
class RoleController {

    @Autowired
    lateinit var roleService: RoleService

    /**
     * 获取角色列表
     *
     * 获取系统中所有角色的列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 角色实体列表
     * @since POST /api/role/list
     */
    @PostMapping("/list")
    suspend fun list(context: Context): List<SysRoleEntity?> {
        return roleService.list(context)
    }


    data class QueryRoleByPageParam(
        val pageNo: Int,
        val pageSize: Int,
        val roleName: String?,
    )

    /**
     * 角色分页查询
     *
     * 分页查询角色列表，支持按角色名称筛选。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param param 分页参数，包含 pageNo（页码）、pageSize（每页数量）、roleName（角色名称，可选）
     * @return 分页数据
     * @since POST /api/role/queryRoleByPage
     */
    @PostMapping("/queryRoleByPage")
    suspend fun queryRoleByPage(context: Context, @RequestBody param: QueryRoleByPageParam): Bundle {
        val (a, b, c) = param
        return roleService.queryRoleByPage(context, a, b, c)
    }

    /**
     * 创建角色
     *
     * 创建一个新的角色。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 角色数据，包含角色名称、描述等信息
     * @return 创建的角色实体
     * @since POST /api/role/create
     */
    @PostMapping("/create")
    suspend fun create(context: Context, @RequestBody params: Bundle): SysRoleEntity {
        return roleService.create(context, params)
    }

    /**
     * 更新角色
     *
     * 更新指定角色的信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 角色更新数据，包含角色ID及需要更新的字段
     * @return 更新结果
     * @since POST /api/role/update
     */
    @PostMapping("/update")
    suspend fun update(context: Context, @RequestBody params: Bundle): Any {
        return roleService.update(context, params)
    }

    data class DestroyParam(var id: Int)

    /**
     * 删除角色
     *
     * 删除指定ID的角色。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（角色ID）的请求体
     * @return 删除操作结果
     * @since POST /api/role/destroy
     */
    @PostMapping("/destroy")
    suspend fun destroy(context: Context, @RequestBody params: DestroyParam): Any {
        return roleService.destroy(context, params.id)
    }
}