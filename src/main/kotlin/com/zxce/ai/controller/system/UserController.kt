package com.zxce.ai.controller.system

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.manager.SessionManager
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.service.system.UserService
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.bundle.invoke
import com.zxce.ai.util.bundle.scope
import com.zxce.ai.util.session.Context

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * 用户管理控制器
 *
 * 提供用户相关的RESTful API，包括用户信息查询、创建、编辑、删除、密码修改、头像/背景图管理等功能。
 * 所有接口路径前缀为 /api/user，需要用户登录认证。
 */
@HostForward
@RestController
@RequestMapping("/api/user")
class UserController {

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var sessionManager: SessionManager

    /**
     * 获取当前用户信息
     *
     * 获取当前登录用户的基本信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 用户实体信息
     * @since POST /api/user/myInfo
     */
    @PostMapping("/myInfo")
    suspend fun myInfo(context: Context): SysUserEntity {
        return userService.myUserInfo(context)
    }

    /**
     * 获取当前用户角色
     *
     * 获取当前登录用户的角色信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 用户角色信息
     * @since POST /api/user/myRole
     */
    @PostMapping("/myRole")
    suspend fun myRole(context: Context): Bundle {
        return userService.myUserRole(context)
    }

    /**
     * 获取当前用户会话
     *
     * 获取当前登录用户的会话信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 会话信息，若不存在则返回null
     * @since POST /api/user/mySession
     */
    @PostMapping("/mySession")
    suspend fun mySession(context: Context): Bundle? {
        return userService.mySession(context)
    }

    class ListParam {
        var pageNo: Int = 0
        var pageSize: Int = 0
        var keys: String? = null
    }

    /**
     * 获取用户列表
     *
     * 分页获取系统用户列表，支持关键词搜索。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param param 分页参数，包含 pageNo（页码）、pageSize（每页数量）、keys（搜索关键词，可选）
     * @return 包含 data（用户列表）和 total（总数）的分页数据
     * @since POST /api/user/list
     */
    @PostMapping("/list")
    suspend fun list(context: Context, @RequestBody param: ListParam): Bundle {
        val (rows, count) = userService.list(context, param.pageNo, param.pageSize, param.keys)
        return bundleScope {
            "data" set rows
            "total" set count
        }
    }

    /**
     * 获取管理员权限范围内的用户列表
     *
     * 分页获取当前用户管理员权限范围内的用户列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param param 分页参数，包含 pageNo（页码）、pageSize（每页数量）、keys（搜索关键词，可选）
     * @return 包含 data（用户列表）和 total（总数）的分页数据
     * @since POST /api/user/listByAdminScope
     */
    @PostMapping("/listByAdminScope")
    suspend fun listByAdminScope(context: Context, @RequestBody param: ListParam): Bundle {
        val (rows, count) = userService.listByAdminScope(context, param.pageNo, param.pageSize, param.keys)
        return bundleScope {
            "data" set rows
            "total" set count
        }
    }

    /**
     * 获取我的管理员权限范围
     *
     * 获取当前用户的管理员权限范围信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 管理员权限范围信息
     * @since POST /api/user/myAdminScope
     */
    @PostMapping("/myAdminScope")
    suspend fun myAdminScope(context: Context): Bundle {
        return userService.getMyAdminScope(context)
    }

    /**
     * 获取可分配的角色列表
     *
     * 获取当前用户可分配给其他用户的角色列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 角色编码列表
     * @since POST /api/user/assignableRoles
     */
    @PostMapping("/assignableRoles")
    suspend fun assignableRoles(context: Context): List<String> {
        return userService.getAssignableRoles(context)
    }

    /**
     * 获取校友列表
     *
     * 获取当前用户的校友（同学）列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 校友信息列表
     * @since POST /api/user/classmates
     */
    @PostMapping("/classmates")
    suspend fun classmates(context: Context): List<Bundle> {
        return userService.getClassmates(context)
    }

    class UserSearchingParam {
        var keys: String? = null
    }

    /**
     * 搜索用户
     *
     * 根据关键词搜索用户。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 搜索参数，包含 keys（搜索关键词，可选）
     * @return 匹配的用户实体列表
     * @since POST /api/user/searching
     */
    @PostMapping("/searching")
    suspend fun userSearching(context: Context, params: UserSearchingParam): List<SysUserEntity> {
        return userService.userSearching(context, params.keys)
    }

    class GetParam {
        var idList: List<String> = emptyList()
    }

    /**
     * 根据ID批量获取用户
     *
     * 根据用户ID列表批量获取用户信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param param 包含 idList（用户ID列表）的请求体
     * @return 用户信息列表
     * @since POST /api/user/get
     */
    @PostMapping("/get")
    suspend fun get(context: Context, @RequestBody param: GetParam): List<Bundle> {
        return userService.get(context, param.idList)
    }

    class CreateParam {
        var phone: String? = null
        var email: String? = null
        var userName: String = ""
        var realName: String? = null
        var password: String? = null
        var isUse: Boolean = true
        var roleId: Int = 4
    }

    /**
     * 创建用户
     *
     * 创建一个新用户（公开注册）。
     *
     * @param param 用户创建参数，包含 userName（用户名）、phone（手机号，可选）、email（邮箱，可选）、realName（真实姓名，可选）、isUse（是否启用）、roleId（角色ID）、password（密码，可选）
     * @return 创建结果
     * @since POST /api/user/create
     */
    @PostMapping("/create")
    suspend fun create(@RequestBody param: CreateParam): Any {
        return userService.createPublic(bundleScope {
            use(param::userName)
            use(param::phone)
            use(param::email)
            use(param::realName)
            use(param::isUse)
            use(param::roleId)
            use(param::password)
        })
    }

    /**
     * 获取用户信息
     *
     * 获取当前登录用户的详细信息及权限。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 用户详细信息及权限列表
     * @since POST /api/user/userInfo
     */
    @PostMapping("/userInfo")
    suspend fun getUserInfo(context: Context): Bundle {
        return userService.getUserInfo(context, context.userId).scope {
            // 我也不知道这句话什么意思。
            "permissions" set listOf(current["userName"])
        }
    }

    /**
     * 获取用户统计数据
     *
     * 获取用户相关的统计数据。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 用户统计数据
     * @since POST /api/user/stats
     */
    @PostMapping("/stats")
    suspend fun getUserStats(context: Context): Bundle {
        return userService.getUserStats(context)
    }

    /**
     * 管理端总览统计（用户数、帖子数、组织数、学校数、职位数、连接数、消息数）。
     * @since POST /api/user/stats/overview
     */
    @PostMapping("/stats/overview")
    suspend fun getOverviewStats(context: Context): Bundle {
        return userService.getOverviewStats(context)
    }

    /**
     * 编辑用户信息
     *
     * 更新指定用户的信息。必须提供用户ID。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 用户更新数据，必须包含 id（用户ID）及需要更新的字段
     * @return 更新结果
     * @since POST /api/user/edit
     */
    @PostMapping("/edit")
    suspend fun edit(context: Context, @RequestBody data: Bundle): Any {
        if ("id" !in data) throw IllegalArgumentException("必须提供UserID")
        data["updatedBy"] = context.userId
        return userService.update(context, data)
    }

    class DeleteParam {
        var ids: List<String> = emptyList()
    }

    /**
     * 批量删除用户
     *
     * 根据ID列表批量删除用户。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 包含 ids（用户ID列表）的请求体
     * @return 删除操作结果
     * @since POST /api/user/delete
     */
    @PostMapping("/delete")
    suspend fun delete(context: Context, @RequestBody data: DeleteParam): Any {
        return userService.delete(context, data.ids)
    }

    class UpdatePasswordParam {
        var id: String? = null
        var origin: String? = null
        var target: String = ""
        var session: String? = null
    }

    /**
     * 修改密码
     *
     * 修改指定用户的密码，支持通过session或当前登录身份认证。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 密码修改参数，包含 id（用户ID）、origin（原密码）、target（新密码）、session（会话ID，可选）
     * @return 修改结果
     * @since POST /api/user/updatePassword
     */
    @PostMapping("/updatePassword")
    suspend fun updatePassword(context: Context, @RequestBody params: UpdatePasswordParam): Any {
        if (params.session != null) {
            val session = params.session!!
            if (!sessionManager.contains(session)) {
                throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登陆")
            }
            val session1 = sessionManager[session]
            params.id = session1.invoke("id")
        } else if (sessionManager.mySession(context) == null) {
            throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登陆")
        }

        return userService.updatePassword(context, params.id!!, params.origin ?: "", params.target)
    }

    /**
     * 获取用户头像
     *
     * 根据用户ID获取头像文件。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（用户ID）的请求体
     * @return 头像文件数据流响应
     * @since POST /api/user/avatar
     */
    @PostMapping("/avatar")
    suspend fun getAvatar(context: Context, @RequestBody params: Map<String, String>): ResponseEntity<Flux<DataBuffer>> {
        return userService.avatar(context, params["id"] ?: "")
    }

    /**
     * 获取用户头像（静态路径）
     *
     * 通过URL路径参数获取用户头像文件。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param userId 用户ID（路径变量）
     * @return 头像文件数据流响应
     * @since GET /api/user/avatar/get/{userId}
     */
    @GetMapping("/avatar/get/{userId}")
    suspend fun getAvatarStatic(
        context: Context,
        @PathVariable userId: String
    ): ResponseEntity<Flux<DataBuffer>> {
        return userService.avatar(context, userId)
    }

    /**
     * 上传用户头像
     *
     * 上传并更新指定用户的头像。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param filePart 上传的头像文件
     * @param id 用户ID
     * @return 上传结果（字节数组）
     * @since PUT /api/user/avatar
     */
    @PutMapping("/avatar")
    suspend fun setAvatar(
        context: Context,
        @RequestPart("file") filePart: Flux<FilePart>,
        @RequestParam("id") id: String
    ): ResponseEntity<ByteArray> {
        val file = filePart.awaitSingle()
        return userService.avatar(context, id, file)
    }

    /**
     * 获取用户背景图（静态路径）
     *
     * 通过URL路径参数获取用户背景图文件。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param userId 用户ID（路径变量）
     * @return 背景图文件数据流响应
     * @since GET /api/user/background/get/{userId}
     */
    @GetMapping("/background/get/{userId}")
    suspend fun getBackgroundStatic(
        context: Context,
        @PathVariable userId: String
    ): ResponseEntity<Flux<DataBuffer>> {
        return userService.background(context, userId)
    }

    /**
     * 上传用户背景图
     *
     * 上传并更新指定用户的背景图。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param filePart 上传的背景图文件
     * @param id 用户ID
     * @return 上传结果（字节数组）
     * @since PUT /api/user/background
     */
    @PutMapping("/background")
    suspend fun setBackground(
        context: Context,
        @RequestPart("file") filePart: Flux<FilePart>,
        @RequestParam("id") id: String
    ): ResponseEntity<ByteArray> {
        val file = filePart.awaitSingle()
        return userService.background(context, id, file)
    }
}