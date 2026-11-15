package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import io.ebean.annotation.DbJson
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_forum_comment")
class AlkForumComment : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_forum_post.id")
    @Column(nullable = false)
    lateinit var postId: UUID

    @DbComment("父评论ID（NULL表示顶级评论）")
    var parentId: UUID? = null

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("评论内容")
    @Column(nullable = false)
    lateinit var content: String

    @DbComment("图片URL数组")
    @DbJson
    var images: List<String>? = null

    @DbComment("点赞数缓存")
    var likesCount: Int = 0

    @DbComment("楼层号")
    var floorNumber: Int = 0
}