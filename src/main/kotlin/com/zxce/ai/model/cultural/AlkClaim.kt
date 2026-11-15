package com.zxce.ai.model.cultural

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_claim")
class AlkClaim : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_claim_item.id")
    @Column(nullable = false)
    lateinit var claimItemId: UUID

    @DbComment("关联sys_user.id，认领人")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("认领数量")
    var quantity: Int = 1

    @DbComment("认领理由")
    var reason: String? = null

    @DbComment("联系方式")
    var contactInfo: String? = null

    @DbComment("状态: pending-待审核, approved-已通过, rejected-已拒绝")
    var status: String = "pending"
}