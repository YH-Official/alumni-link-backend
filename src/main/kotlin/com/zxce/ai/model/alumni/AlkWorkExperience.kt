package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_work_experience")
class AlkWorkExperience : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("公司名称")
    @Column(nullable = false)
    lateinit var companyName: String

    @DbComment("关联alk_company.id")
    var companyId: UUID? = null

    @DbComment("职位")
    var position: String? = null

    @DbComment("行业")
    var industry: String? = null

    @DbComment("入职日期")
    var startDate: java.sql.Date? = null

    @DbComment("离职日期（NULL表示在职）")
    var endDate: java.sql.Date? = null

    @DbComment("是否当前工作")
    @JvmField
    var isCurrent: Boolean = false
}