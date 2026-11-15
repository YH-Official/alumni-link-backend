package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_forum_share")
class AlkForumShare : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("关联alk_forum_post.id")
    @Column(nullable = false)
    lateinit var postId: UUID

    @DbComment("转发类型: 1=直接转发 2=引用转发")
    @Column(nullable = false)
    var shareType: Int = 1

    @DbComment("引用转发时的附加内容")
    var content: String? = null
}