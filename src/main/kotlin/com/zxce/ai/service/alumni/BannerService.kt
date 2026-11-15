package com.zxce.ai.service.alumni

import com.zxce.ai.model.alumni.AlkBanner
import com.zxce.ai.model.alumni.query.QAlkBanner
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.*

/**
 * 首页 Banner 服务
 *
 * 提供首页轮播 Banner 的查询（前台启用列表 / 管理端全量列表）与管理员维护能力。
 */
@Service
class BannerService {

    /**
     * 获取启用中的 Banner 列表（按 sort_order 升序）
     */
    suspend fun list(ctx: Context): List<Bundle> = ctx {
        ctx.query<QAlkBanner>()
            .isEnabled.eq(true)
            .orderBy().sortOrder.asc()
            .toAsync().findList()
            .map { toBundle(it) }
    }

    /**
     * 获取全部 Banner（含禁用），仅管理员
     */
    suspend fun all(ctx: Context): List<Bundle> = ctx {
        requireAdmin(ctx)
        ctx.query<QAlkBanner>()
            .orderBy().sortOrder.asc()
            .toAsync().findList()
            .map { toBundle(it, withEnabled = true) }
    }

    /**
     * 新增或更新 Banner（有 id 即为更新），仅管理员
     */
    suspend fun save(ctx: Context, data: Bundle): Any = ctx {
        requireAdmin(ctx)

        val idStr = data["id"]?.toString()?.trim().orEmpty()
        val title = data["title"]?.toString()?.trim().orEmpty()
        if (title.isEmpty()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "标题不能为空")

        val banner = if (idStr.isNotEmpty()) {
            ctx.query<QAlkBanner>().id.eq(idStr.toUuid()).findOne()
                ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "Banner 不存在")
        } else {
            ctx.createModel<AlkBanner> { id = UUID.randomUUID() }
        }

        banner.title = title
        banner.subtitle = data["subtitle"]?.toString()
        banner.imageUrl = data["imageUrl"]?.toString()
        (data["targetType"] as? Number)?.toInt()?.let { banner.targetType = it }
        banner.targetId = data["targetId"]?.toString()?.takeIf { it.isNotBlank() }
        banner.linkUrl = data["linkUrl"]?.toString()
        (data["sortOrder"] as? Number)?.toInt()?.let { banner.sortOrder = it }
        (data["isEnabled"] as? Boolean)?.let { banner.isEnabled = it }
        banner.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        if (idStr.isEmpty()) banner.createdBy = ctx.userIdOrNull ?: ctx.user.id

        withContext(Dispatchers.IO) { banner.save() }
        toBundle(banner, withEnabled = true)
    }

    /**
     * 逻辑删除 Banner，仅管理员
     */
    suspend fun delete(ctx: Context, id: String): Any = ctx {
        requireAdmin(ctx)
        val banner = ctx.query<QAlkBanner>().id.eq(id.toUuid()).findOne()
            ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "Banner 不存在")
        withContext(Dispatchers.IO) { banner.delete() }
        success()
    }

    private fun requireAdmin(ctx: Context) {
        if (!ctx.user.isAdmin) throw ErrorResponseException(HttpStatus.FORBIDDEN, "仅管理员可操作")
    }

    private fun toBundle(banner: AlkBanner, withEnabled: Boolean = false): Bundle = bundleScope {
        "id" set banner.id.toString()
        "title" set banner.title
        "subtitle" set banner.subtitle
        "imageUrl" set banner.imageUrl
        "targetType" set banner.targetType
        "targetId" set banner.targetId
        "linkUrl" set banner.linkUrl
        "sortOrder" set banner.sortOrder
        if (withEnabled) "isEnabled" set banner.isEnabled
    }
}
