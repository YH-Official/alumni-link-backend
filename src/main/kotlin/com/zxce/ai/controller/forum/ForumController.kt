package com.zxce.ai.controller.forum

import com.zxce.ai.annotation.HostForward

import com.zxce.ai.service.forum.ForumService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * 校友论坛控制器
 *
 * 提供论坛相关的RESTful API，包括分区管理、帖子管理、评论管理、互动（点赞/分享/收藏）和统计等功能。
 * 所有接口路径前缀为 /api/alumni/forum，需要用户登录认证。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/forum")
class ForumController {

    @Autowired
    lateinit var forumService: ForumService

    // ============================================================
    // 分区管理
    // ============================================================

    /**
     * 获取分区列表
     *
     * 获取所有论坛分区（板块）的列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 分区列表数据
     * @since POST /api/alumni/forum/category/list
     */
    @PostMapping("/category/list")
    suspend fun categoryList(context: Context): Any {
        return forumService.getCategoryList(context)
    }

    data class FollowParam(val categoryId: UUID)

    /**
     * 关注/取消关注分区
     *
     * 切换当前用户对指定分区的关注状态（已关注则取消，未关注则关注）。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 categoryId（分区ID）的请求体
     * @return 操作结果
     * @since POST /api/alumni/forum/category/follow
     */
    @PostMapping("/category/follow")
    suspend fun toggleFollow(context: Context, @RequestBody params: FollowParam): Any {
        return forumService.toggleFollowCategory(context, params.categoryId)
    }

    /**
     * 获取我关注的分区列表
     *
     * 获取当前用户已关注的所有分区。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 已关注的分区列表
     * @since POST /api/alumni/forum/category/myFollowed
     */
    @PostMapping("/category/myFollowed")
    suspend fun myFollowed(context: Context): Any {
        return forumService.getMyFollowedCategories(context)
    }

    /**
     * 从组织同步分区
     *
     * 从组织架构系统同步分区数据到论坛系统。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 同步结果
     * @since POST /api/alumni/forum/category/sync
     */
    @PostMapping("/category/sync")
    suspend fun syncCategories(context: Context): Any {
        return forumService.syncCategoriesFromOrganization(context)
    }

    /**
     * 创建分区
     *
     * 创建一个新的论坛分区。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 分区数据，包含分区名称、描述等信息
     * @return 创建的分区信息
     * @since POST /api/alumni/forum/category/create
     */
    @PostMapping("/category/create")
    suspend fun createCategory(context: Context, @RequestBody data: Bundle): Any {
        return forumService.createCategory(context, data)
    }

    /**
     * 获取组织列表
     *
     * 获取可用的组织列表，用于关联分区。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 组织列表数据
     * @since POST /api/alumni/forum/category/organizations
     */
    @PostMapping("/category/organizations")
    suspend fun organizationList(context: Context): Any {
        return forumService.getOrganizationList(context)
    }

    /**
     * 获取我加入的组织分区
     *
     * 获取当前用户已加入组织对应的分区列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 已加入组织的分区列表
     * @since POST /api/alumni/forum/category/myJoinedOrganizations
     */
    @PostMapping("/category/myJoinedOrganizations")
    suspend fun myJoinedOrganizations(context: Context): Any {
        return forumService.getMyJoinedOrganizationCategories(context)
    }

    /**
     * 获取可发帖分区列表
     *
     * 获取当前用户有权限发帖的分区列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 可发帖分区列表
     * @since POST /api/alumni/forum/category/postingList
     */
    @PostMapping("/category/postingList")
    suspend fun postingList(context: Context): Any {
        return forumService.getPostingCategories(context)
    }

    // ============================================================
    // 帖子管理
    // ============================================================

    /**
     * 创建帖子
     *
     * 在指定分区下创建一条新帖子。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 帖子数据，包含标题、内容、分区ID等
     * @return 创建的帖子信息
     * @since POST /api/alumni/forum/post/create
     */
    @PostMapping("/post/create")
    suspend fun createPost(context: Context, @RequestBody data: Bundle): Any {
        return forumService.createPost(context, data)
    }

    /**
     * 转发帖子
     *
     * 对指定帖子进行转发操作。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 转发参数，包含目标帖子ID及转发内容
     * @return 转发结果
     * @since POST /api/alumni/forum/post/repost
     */
    @PostMapping("/post/repost")
    suspend fun repostPost(context: Context, @RequestBody data: Bundle): Any {
        return forumService.repostPost(context, data)
    }

    data class PostIdParam(val id: UUID)

    /**
     * 删除帖子
     *
     * 删除指定ID的帖子。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（帖子ID）的请求体
     * @return 删除操作结果
     * @since POST /api/alumni/forum/post/delete
     */
    @PostMapping("/post/delete")
    suspend fun deletePost(context: Context, @RequestBody params: PostIdParam): Any {
        return forumService.deletePost(context, params.id)
    }

    /**
     * 获取帖子详情
     *
     * 获取指定ID帖子的详细信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（帖子ID）的请求体
     * @return 帖子详情数据
     * @since POST /api/alumni/forum/post/detail
     */
    @PostMapping("/post/detail")
    suspend fun postDetail(context: Context, @RequestBody params: PostIdParam): Any {
        return forumService.getPostDetail(context, params.id)
    }

    data class PostListParam(
        val categoryId: UUID?,
        val pageNo: Int,
        val pageSize: Int,
        val sortBy: String? = "latest"
    )

    /**
     * 获取帖子列表
     *
     * 分页获取帖子列表，可按分区筛选并支持多种排序方式。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 categoryId（分区ID，可选）、pageNo（页码）、pageSize（每页数量）、sortBy（排序方式）
     * @return 包含 data（帖子列表）和 total（总数）的分页数据
     * @since POST /api/alumni/forum/post/list
     */
    @PostMapping("/post/list")
    suspend fun postList(context: Context, @RequestBody params: PostListParam): Any {
        val (data, total) = forumService.getPostList(
            context, params.categoryId, params.pageNo, params.pageSize, params.sortBy
        )
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    data class SearchParam(
        val keyword: String,
        val pageNo: Int,
        val pageSize: Int
    )

    /**
     * 搜索帖子
     *
     * 根据关键词搜索帖子，支持分页。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 搜索参数，包含 keyword（搜索关键词）、pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（搜索结果列表）和 total（总数）的分页数据
     * @since POST /api/alumni/forum/post/search
     */
    @PostMapping("/post/search")
    suspend fun searchPosts(context: Context, @RequestBody params: SearchParam): Any {
        val (data, total) = forumService.searchPosts(context, params.keyword, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    data class StickyParam(val id: UUID, val isSticky: Boolean)

    /**
     * 设置帖子置顶
     *
     * 设置或取消帖子的置顶状态。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（帖子ID）和 isSticky（是否置顶）的请求体
     * @return 操作结果
     * @since POST /api/alumni/forum/post/sticky
     */
    @PostMapping("/post/sticky")
    suspend fun setSticky(context: Context, @RequestBody params: StickyParam): Any {
        return forumService.setPostSticky(context, params.id, params.isSticky)
    }

    data class EssenceParam(val id: UUID, val isEssence: Boolean)

    /**
     * 设置帖子精华
     *
     * 设置或取消帖子的精华标记。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（帖子ID）和 isEssence（是否精华）的请求体
     * @return 操作结果
     * @since POST /api/alumni/forum/post/essence
     */
    @PostMapping("/post/essence")
    suspend fun setEssence(context: Context, @RequestBody params: EssenceParam): Any {
        return forumService.setPostEssence(context, params.id, params.isEssence)
    }

    // ============================================================
    // 评论管理
    // ============================================================

    /**
     * 添加评论
     *
     * 对指定帖子添加一条新评论。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 评论数据，包含帖子ID、评论内容等
     * @return 创建的评论信息
     * @since POST /api/alumni/forum/comment/add
     */
    @PostMapping("/comment/add")
    suspend fun addComment(context: Context, @RequestBody data: Bundle): Any {
        return forumService.addComment(context, data)
    }

    /**
     * 删除评论
     *
     * 删除指定ID的评论。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（评论ID）的请求体
     * @return 删除操作结果
     * @since POST /api/alumni/forum/comment/delete
     */
    @PostMapping("/comment/delete")
    suspend fun deleteComment(context: Context, @RequestBody params: PostIdParam): Any {
        return forumService.deleteComment(context, params.id)
    }

    data class CommentListParam(
        val postId: UUID,
        val pageNo: Int,
        val pageSize: Int
    )

    /**
     * 获取评论列表
     *
     * 分页获取指定帖子的评论列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 postId（帖子ID）、pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（评论列表）和 total（总数）的分页数据
     * @since POST /api/alumni/forum/comment/list
     */
    @PostMapping("/comment/list")
    suspend fun commentList(context: Context, @RequestBody params: CommentListParam): Any {
        val (data, total) = forumService.getCommentList(context, params.postId, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    data class CommentRepliesParam(
        val commentId: UUID,
        val pageNo: Int,
        val pageSize: Int
    )

    /**
     * 获取评论回复列表
     *
     * 分页获取指定评论的回复列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 commentId（评论ID）、pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（回复列表）和 total（总数）的分页数据
     * @since POST /api/alumni/forum/comment/replies
     */
    @PostMapping("/comment/replies")
    suspend fun commentReplies(context: Context, @RequestBody params: CommentRepliesParam): Any {
        val (data, total) = forumService.getCommentReplies(context, params.commentId, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    // ============================================================
    // 互动
    // ============================================================

    data class LikeParam(
        val targetType: Int,
        val targetId: UUID
    )

    /**
     * 切换点赞状态
     *
     * 对指定目标（帖子或评论）进行点赞或取消点赞操作。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 targetType（目标类型：1-帖子，2-评论）和 targetId（目标ID）的请求体
     * @return 操作结果
     * @since POST /api/alumni/forum/like/toggle
     */
    @PostMapping("/like/toggle")
    suspend fun toggleLike(context: Context, @RequestBody params: LikeParam): Any {
        return forumService.toggleLike(context, params.targetType, params.targetId)
    }

    /**
     * 检查点赞状态
     *
     * 检查当前用户对指定目标是否已点赞。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 targetType（目标类型）和 targetId（目标ID）的请求体
     * @return 包含 liked（是否已点赞）的结果
     * @since POST /api/alumni/forum/like/check
     */
    @PostMapping("/like/check")
    suspend fun checkLike(context: Context, @RequestBody params: LikeParam): Any {
        return bundleScope {
            "liked" set forumService.checkLike(context, params.targetType, params.targetId)
        }
    }

    data class ShareCreateParam(
        val postId: UUID,
        val shareType: Int,
        val content: String? = null
    )

    /**
     * 创建分享
     *
     * 对指定帖子创建分享记录。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 postId（帖子ID）、shareType（分享类型）、content（分享内容，可选）的请求体
     * @return 分享创建结果
     * @since POST /api/alumni/forum/share/create
     */
    @PostMapping("/share/create")
    suspend fun createShare(context: Context, @RequestBody params: ShareCreateParam): Any {
        return forumService.createShare(context, bundleScope {
            "postId" set params.postId
            "shareType" set params.shareType
            params.content?.let { "content" set it }
        })
    }

    data class PostIdListParam(val postId: UUID)

    /**
     * 获取分享列表
     *
     * 获取指定帖子的分享记录列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 postId（帖子ID）的请求体
     * @return 分享列表数据
     * @since POST /api/alumni/forum/share/list
     */
    @PostMapping("/share/list")
    suspend fun shareList(context: Context, @RequestBody params: PostIdListParam): Any {
        return forumService.getShareList(context, params.postId)
    }

    data class CollectionToggleParam(val postId: UUID)

    /**
     * 切换收藏状态
     *
     * 对指定帖子进行收藏或取消收藏操作。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 postId（帖子ID）的请求体
     * @return 操作结果
     * @since POST /api/alumni/forum/collection/toggle
     */
    @PostMapping("/collection/toggle")
    suspend fun toggleCollection(context: Context, @RequestBody params: CollectionToggleParam): Any {
        return forumService.toggleCollection(context, params.postId)
    }

    data class CollectionListParam(
        val pageNo: Int,
        val pageSize: Int
    )

    /**
     * 获取收藏列表
     *
     * 分页获取当前用户的收藏列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（收藏列表）和 total（总数）的分页数据
     * @since POST /api/alumni/forum/collection/list
     */
    @PostMapping("/collection/list")
    suspend fun collectionList(context: Context, @RequestBody params: CollectionListParam): Any {
        val (data, total) = forumService.getCollectionList(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    // ============================================================
    // 统计
    // ============================================================

    /**
     * 获取学校论坛统计数据
     *
     * 获取学校论坛的整体统计数据，如帖子数、用户数等。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 学校论坛统计数据
     * @since POST /api/alumni/forum/stats/school
     */
    @PostMapping("/stats/school")
    suspend fun schoolStats(context: Context): Any {
        return forumService.getSchoolStats(context)
    }
}