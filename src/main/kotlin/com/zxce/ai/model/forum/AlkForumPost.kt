package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import io.ebean.annotation.DbJson
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.Timestamp
import java.util.*

@Entity
@Table(name = "alk_forum_post")
class AlkForumPost : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_forum_category.id")
    @Column(nullable = false)
    lateinit var categoryId: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("帖子标题")
    @Column(nullable = false)
    lateinit var title: String

    @DbComment("帖子正文")
    var content: String? = null

    @DbComment("图片URL数组")
    @DbJson
    var images: List<String>? = null

    @DbComment("是否置顶")
    var isSticky: Boolean = false

    @DbComment("是否精华")
    var isEssence: Boolean = false

    @DbComment("是否锁定")
    var isLocked: Boolean = false

    @DbComment("浏览量")
    var viewsCount: Int = 0

    @DbComment("点赞数缓存")
    var likesCount: Int = 0

    @DbComment("评论数缓存")
    var commentsCount: Int = 0

    @DbComment("转发数缓存")
    var sharesCount: Int = 0

    @DbComment("被转发的原帖ID（用于转发到我的发帖功能）")
    var repostFromId: UUID? = null

    @DbComment("最后回复时间")
    var lastRepliedAt: Timestamp? = null
}