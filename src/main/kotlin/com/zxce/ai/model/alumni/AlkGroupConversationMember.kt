package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_group_conversation_member")
class AlkGroupConversationMember : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("群聊ID")
    @Column(nullable = false)
    var groupConversationId: UUID = UUID.randomUUID()

    @DbComment("用户ID")
    @Column(nullable = false)
    var userId: UUID = UUID.randomUUID()

    @DbComment("是否为管理员（同级管理员可以发消息）")
    var isAdmin: Boolean = false

    @DbComment("未读消息数")
    var unreadCount: Int = 0

    @DbComment("最后已读消息时间")
    var lastReadAt: Date? = null

    @DbComment("是否静音")
    var isMuted: Boolean = false

    @DbComment("是否置顶")
    var isTop: Boolean = false
}
