package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_forum_like")
class AlkForumLike : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("点赞对象类型: 1=帖子 2=评论")
    @Column(nullable = false)
    var targetType: Int = 1

    @DbComment("点赞对象ID")
    @Column(nullable = false)
    lateinit var targetId: UUID
}