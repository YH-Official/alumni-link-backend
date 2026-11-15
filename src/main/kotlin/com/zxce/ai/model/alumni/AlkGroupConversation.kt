package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_group_conversation")
class AlkGroupConversation : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("群聊名称（如：XX学院管理员群）")
    var groupName: String? = null

    @DbComment("群聊范围类型：school-学校 college-学院 major-专业 class-班级")
    @Column(nullable = false)
    var scopeType: String = ""

    @DbComment("群聊范围ID（学校ID/学院ID/专业ID/班级ID）")
    @Column(nullable = false)
    var scopeId: UUID = UUID.randomUUID()

    @DbComment("创建者用户ID")
    @Column(nullable = false)
    var creatorId: UUID = UUID.randomUUID()

    @DbComment("最后一条消息内容（缓存）")
    var lastMessage: String? = null

    @DbComment("最后一条消息时间")
    var lastMessageAt: Date? = null

    @DbComment("群成员数量")
    var memberCount: Int = 0
}
