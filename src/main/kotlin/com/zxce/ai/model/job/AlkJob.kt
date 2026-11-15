package com.zxce.ai.model.job

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.*

@Entity
@Table(name = "alk_job")
class AlkJob : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_company.id")
    @Column(nullable = false)
    lateinit var companyId: UUID

    @DbComment("职位名称")
    @Column(nullable = false)
    lateinit var title: String

    @DbComment("工作地点")
    var workLocation: String? = null

    @DbComment("最低薪资(K)")
    var salaryMin: BigDecimal? = null

    @DbComment("最高薪资(K)")
    var salaryMax: BigDecimal? = null

    @DbComment("薪资类型: 1-月薪 2-年薪 3-日薪")
    var salaryType: Int = 1

    @DbComment("学历要求: 不限/专科/本科/硕士/博士")
    var educationRequirement: String? = null

    @DbComment("经验要求: 不限/应届生/1-3年/3-5年/5-10年/10年以上")
    var experienceRequirement: String? = null

    @DbComment("工作类型: 全职/兼职/实习")
    var jobType: String? = null

    @DbComment("职位描述")
    var description: String? = null

    @DbComment("任职要求")
    var requirements: String? = null

    @DbComment("福利待遇（逗号分隔）")
    var benefits: String? = null

    @DbComment("职位标签（逗号分隔）")
    var tags: String? = null

    @DbComment("是否发布")
    @JvmField
    var isActive: Boolean = true

    @DbComment("浏览量（缓存）")
    var viewsCount: Int = 0

    @DbComment("投递数（缓存）")
    var applicationsCount: Int = 0
}