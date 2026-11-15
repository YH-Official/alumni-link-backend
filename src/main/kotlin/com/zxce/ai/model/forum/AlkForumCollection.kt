package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_forum_collection")
class AlkForumCollection : SystemModel() {
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
}