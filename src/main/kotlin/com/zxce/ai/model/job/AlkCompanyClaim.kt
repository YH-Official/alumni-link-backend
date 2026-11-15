package com.zxce.ai.model.job

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_company_claim")
class AlkCompanyClaim : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_company.id")
    @Column(nullable = false)
    lateinit var companyId: UUID

    @DbComment("认领人（关联sys_user.id）")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("企业邮箱")
    @Column(nullable = false)
    lateinit var workEmail: String

    @DbComment("邮箱验证码是否已通过")
    @JvmField
    var verifyPassed: Boolean = false

    @DbComment("证明材料文件ID（关联oss_user_file.id）")
    var proofFileId: UUID? = null

    @DbComment("审核状态: 1-待审核 2-通过 3-拒绝")
    var status: Int = 1

    @DbComment("审核备注")
    var remark: String? = null
}
