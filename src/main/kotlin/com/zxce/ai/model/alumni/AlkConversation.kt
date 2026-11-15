package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_conversation")
class AlkConversation : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var targetUserId: UUID

    @DbComment("最后一条消息（缓存）")
    var lastMessage: String? = null

    @DbComment("未读消息数")
    var unreadCount: Int = 0

    @DbComment("是否置顶")
    @JvmField
    var isTop: Boolean = false

    @DbComment("是否静音")
    @JvmField
    var isMuted: Boolean = false

    @DbComment("是否被拒绝（接收方拒绝申请后置为 true，发送方不能再发消息）")
    @JvmField
    var isRejected: Boolean = false
}