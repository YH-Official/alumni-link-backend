package com.zxce.ai.controller.forum

import com.zxce.ai.annotation.JwtIgnore
import com.zxce.ai.service.forum.ForumService
import com.zxce.ai.util.session.SSEContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * 公开论坛控制器
 *
 * 用于处理分享链接的公开访问，无需用户登录认证即可访问帖子详情。
 * 所有接口路径前缀为 /api/forum。
 */
@RestController
@RequestMapping("/api/forum")
class PublicForumController {

    @Autowired
    lateinit var forumService: ForumService

    @Autowired
    lateinit var applicationContext: ApplicationContext

    /**
     * 公开的帖子详情接口
     *
     * 用于分享链接的公开访问，无需登录认证即可查看帖子详情。
     *
     * @param id 帖子ID（UUID）
     * @return 帖子详情数据
     * @since GET /api/forum/post/{id}
     */
    @JwtIgnore
    @GetMapping("/post/{id}")
    suspend fun publicPostDetail(@PathVariable id: UUID): Any {
        val ctx = SSEContext(applicationContext)
        return forumService.getPostDetail(ctx, id)
    }
}