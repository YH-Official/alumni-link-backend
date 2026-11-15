package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_organization_member")
class AlkOrganizationMember : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("组织ID")
    @Column(nullable = false)
    lateinit var organizationId: UUID

    @DbComment("用户ID")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("角色: 0-普通成员 1-管理员")
    var role: Int = 0

    @DbComment("状态: 0-待审核 1-已加入 2-已拒绝")
    var status: Int = 0
}