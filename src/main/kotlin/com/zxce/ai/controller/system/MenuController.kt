package com.zxce.ai.controller.system

import com.zxce.ai.service.system.MenuService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 菜单管理控制器
 *
 * 提供菜单配置相关的RESTful API，用于获取系统菜单配置信息。
 * 接口路径前缀为 /api/menu。
 */
@RestController
@RequestMapping("/api/menu")
class MenuController {

    @Autowired
    lateinit var service: MenuService

    /**
     * 获取菜单配置
     *
     * 获取系统菜单的完整配置信息，包括菜单结构、权限标识等。
     *
     * @param ctx 会话上下文
     * @return 菜单配置数据
     * @since POST /api/menu/config
     */
    @PostMapping("/config")
    fun getMenuConfig(ctx: Context): Bundle {
        return service.getMenuConfig()
    }
}