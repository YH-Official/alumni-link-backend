package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.Timestamp
import java.util.*

@Entity
@Table(name = "alk_forum_user_follow_category")
class AlkForumUserFollowCategory : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("关联alk_forum_category.id")
    @Column(nullable = false)
    lateinit var categoryId: UUID

    @DbComment("关注时间")
    var followedAt: Timestamp? = null

    @DbComment("是否隐藏（已关注但不在Tab栏显示）")
    var isHidden: Boolean = false
}