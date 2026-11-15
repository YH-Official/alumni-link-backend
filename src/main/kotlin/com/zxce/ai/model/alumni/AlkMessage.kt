package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.Timestamp
import java.util.*

@Entity
@Table(name = "alk_message")
class AlkMessage : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_conversation.id")
    @Column(nullable = false)
    lateinit var conversationId: UUID

    @DbComment("关联alk_group_conversation.id（群聊消息时使用）")
    var groupConversationId: UUID? = null

    @DbComment("关联组织ID（群发消息时使用）")
    var organizationId: UUID? = null

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var senderId: UUID

    @DbComment("关联sys_user.id（群聊时为接收者用户ID，可空表示群消息）")
    var receiverId: UUID? = null

    @DbComment("消息内容")
    @Column(nullable = false)
    lateinit var content: String

    @DbComment("消息类型: 1-文本 2-图片 3-文件 4-语音 5-群发消息 6-群聊消息")
    var messageType: Int = 1

    @DbComment("是否已读")
    @JvmField
    var isRead: Boolean = false

    @DbComment("发送状态: 1-发送中 2-已发送 3-发送失败")
    var sendStatus: Int = 1

    @DbComment("是否已通过SSE推送")
    @JvmField
    var isSsePushed: Boolean = false

    @DbComment("是否已撤回")
    var isRecalled: Boolean = false

    @DbComment("撤回时间")
    var recalledAt: Timestamp? = null
}