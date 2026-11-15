package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_connection")
class AlkConnection : SystemModel() {
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

    @DbComment("关系状态: 0-未关注 1-A关注B 2-B关注A 3-互关(好友)")
    var status: Int = 0

    @DbComment("关联度（0-100，动态计算后缓存）")
    var connectionDegree: Int = 0

    @DbComment("关联原因描述")
    var connectionReason: String? = null
}