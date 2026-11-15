package com.zxce.ai.model.job

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_company")
class AlkCompany : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("企业名称")
    @Column(nullable = false)
    lateinit var name: String

    @DbComment("企业logo")
    var logo: String? = null

    @DbComment("所属行业")
    var industry: String? = null

    @DbComment("企业规模: 1-50人 2-50-100人 3-100-500人 4-500-1000人 5-1000人以上")
    var scale: String? = null

    @DbComment("企业简介")
    var description: String? = null

    @DbComment("办公地址")
    var address: String? = null

    @DbComment("企业官网")
    var website: String? = null

    @DbComment("联系人")
    var contactName: String? = null

    @DbComment("联系电话")
    var contactPhone: String? = null

    @DbComment("联系邮箱")
    var contactEmail: String? = null

    @DbComment("是否已认证")
    @JvmField
    var isVerified: Boolean = false

    @DbComment("认领状态: 0-未认领 1-待审核 2-已认证 3-认领失效")
    var claimStatus: Int = 0

    @DbComment("认领人（关联sys_user.id）")
    var ownerId: UUID? = null

    @DbComment("认领邮箱后缀")
    var emailDomain: String? = null

    @DbComment("认领通过时间")
    var claimedAt: java.sql.Timestamp? = null
}