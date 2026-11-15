package com.zxce.ai.controller.alumni

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.alumni.BannerService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 首页 Banner 控制器
 *
 * 提供首页轮播 Banner 的查询与管理员维护接口。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/banner")
class BannerController {

    @Autowired
    lateinit var bannerService: BannerService

    /**
     * 获取启用中的 Banner 列表（按 sortOrder 升序）
     * HTTP方法: GET
     */
    @GetMapping("/list")
    suspend fun list(context: Context): List<Bundle> {
        return bannerService.list(context)
    }

    /**
     * 获取全部 Banner（含禁用），仅管理员
     * HTTP方法: GET
     */
    @GetMapping("/all")
    suspend fun all(context: Context): List<Bundle> {
        return bannerService.all(context)
    }

    /**
     * 新增或更新 Banner（有 id 即更新），仅管理员
     * HTTP方法: POST
     */
    @PostMapping("/save")
    suspend fun save(context: Context, @RequestBody data: Bundle): Any {
        return bannerService.save(context, data)
    }

    data class DeleteParam(
        var id: String = ""
    )

    /**
     * 逻辑删除 Banner，仅管理员
     * HTTP方法: POST
     */
    @PostMapping("/delete")
    suspend fun delete(context: Context, @RequestBody params: DeleteParam): Any {
        return bannerService.delete(context, params.id)
    }
}
