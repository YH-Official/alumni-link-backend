package com.zxce.ai.controller.admin

import com.zxce.ai.service.admin.DatabaseAdminService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleOf
import com.zxce.ai.util.html
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 数据库管理页。
 *
 * - `GET /admin/db` 返回页面本身，静态 HTML，不带任何数据；
 * - `POST /api/admin/db/` 下的接口是页面用来取数写数的，走既有的 JwtWebFilter，额外要求超管角色。
 *
 * 页面用 `Authorization: <会话ID>` 调接口，和前端登录拿到的是同一个会话令牌，
 * 所以不需要为它单独再做一套账号体系，也没有 Cookie，天然没有 CSRF 面。
 */
@RestController
class DatabaseAdminController(
    private val service: DatabaseAdminService,
) {

    companion object {
        private const val PAGE_RESOURCE = "static/admin/db/index.html"

        /** 只有超管（sys_role.type = admin）能用，校级/院级管理员都不行。 */
        private const val SUPER_ADMIN_TYPE = "admin"
    }

    private val logger = LoggerFactory.getLogger(DatabaseAdminController::class.java)

    private val pageHtml: String by lazy {
        ClassPathResource(PAGE_RESOURCE).inputStream.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------------ 页面

    /**
     * 数据库管理页本体。
     *
     * 页面是静态的、不含业务数据，所以不拦登录；真正的数据接口都在 /api/admin/db 下鉴权。
     *
     * @return HTML 页面
     */
    @GetMapping("/admin/db", "/admin/db/")
    suspend fun page(): ResponseEntity<String> = html(pageHtml)

    // ------------------------------------------------------------------ 接口

    /**
     * 校验当前会话是不是超管，顺带返回页面顶部要展示的身份信息。
     *
     * @param ctx 请求上下文
     * @return 当前账号的身份与库信息
     */
    @PostMapping("/api/admin/db/session")
    suspend fun session(ctx: Context): Bundle {
        val user = ctx.requireSuperAdmin()
        val schema = io { service.schema() }
        return bundleOf(
            "userName" to user.userName,
            "realName" to user.realName,
            "roleName" to user.roleName,
            "roleType" to user.roleType,
            "database" to schema.database,
            "serverVersion" to schema.serverVersion,
        )
    }

    /**
     * 表目录：结构 + 行数 + 能否编辑。
     *
     * @param ctx 请求上下文
     * @return 整库表清单
     */
    @PostMapping("/api/admin/db/catalog")
    suspend fun catalog(ctx: Context): Bundle {
        ctx.requireSuperAdmin()
        return io { service.catalog() }
    }

    /**
     * 分页浏览一张表。
     *
     * @param ctx 请求上下文
     * @param param 表名、页码、每页条数、筛选列与筛选值
     * @return 行数据与分页信息
     */
    @PostMapping("/api/admin/db/browse")
    suspend fun browse(ctx: Context, @RequestBody param: BrowseParam): Bundle {
        ctx.requireSuperAdmin()
        return io {
            service.browse(param.table, param.page, param.size, param.filterColumn, param.filterValue)
        }
    }

    /**
     * 按主键读一行，供编辑表单回显。
     *
     * @param ctx 请求上下文
     * @param param 表名与主键值
     * @return 单行数据与列信息
     */
    @PostMapping("/api/admin/db/row")
    suspend fun row(ctx: Context, @RequestBody param: RowParam): Bundle {
        ctx.requireSuperAdmin()
        return io { service.row(param.table, param.pk) }
    }

    /**
     * 表的建表语句。
     *
     * @param ctx 请求上下文
     * @param param 表名
     * @return 建表语句
     */
    @PostMapping("/api/admin/db/ddl")
    suspend fun ddl(ctx: Context, @RequestBody param: TableParam): Bundle {
        ctx.requireSuperAdmin()
        return bundleOf("ddl" to io { service.ddl(param.table) })
    }

    /**
     * 新增或保存一行。pk 为空表示新增。
     *
     * @param ctx 请求上下文
     * @param param 表名、主键、列值与显式置空的列
     * @return 操作结果提示
     */
    @PostMapping("/api/admin/db/save")
    suspend fun save(ctx: Context, @RequestBody param: SaveParam): Bundle {
        val user = ctx.requireSuperAdmin()
        val values = param.values.mapValues { (_, v) -> v?.toString() }
        val message = io { service.save(param.table, param.pk, values, param.nulls) }
        logger.info("数据库管理页保存数据: user=${user.userName}, table=${param.table}, pk=${param.pk ?: "新增"}")
        return bundleOf("message" to message)
    }

    /**
     * 按主键删除一行。
     *
     * @param ctx 请求上下文
     * @param param 表名与主键值
     * @return 操作结果提示
     */
    @PostMapping("/api/admin/db/delete")
    suspend fun delete(ctx: Context, @RequestBody param: RowParam): Bundle {
        val user = ctx.requireSuperAdmin()
        val message = io { service.delete(param.table, param.pk) }
        logger.warn("数据库管理页删除数据: user=${user.userName}, table=${param.table}, pk=${param.pk}")
        return bundleOf("message" to message)
    }

    // ------------------------------------------------------------------ 内部

    /** 放开 JDBC 这种阻塞调用，别把 Netty 的事件循环线程占住。 */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** 超管校验：JwtWebFilter 只保证「有会话」，这里再卡角色。 */
    private fun Context.requireSuperAdmin(): UserInfo {
        val user = try {
            this.user
        } catch (e: Throwable) {
            throw IllegalAccessException("当前账号没有角色信息，无法使用数据库管理页")
        }
        if (user.roleType != SUPER_ADMIN_TYPE) {
            throw IllegalAccessException("只有超级管理员可以使用数据库管理页")
        }
        return user
    }

    /** 浏览参数。 */
    class BrowseParam {
        var table: String = ""
        var page: Int? = null
        var size: Int? = null
        var filterColumn: String? = null
        var filterValue: String? = null
    }

    /** 单行定位参数（读、删共用）。 */
    class RowParam {
        var table: String = ""
        var pk: String = ""
    }

    /** 只带表名的参数。 */
    class TableParam {
        var table: String = ""
    }

    /** 保存参数。values 用 Any? 接，兼容前端把数字/布尔直接塞进来的情况。 */
    class SaveParam {
        var table: String = ""
        var pk: String? = null
        var values: Map<String, Any?> = emptyMap()
        var nulls: List<String> = emptyList()
    }
}