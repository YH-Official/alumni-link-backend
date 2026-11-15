package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_education")
class AlkEducation : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("学校名称")
    @Column(nullable = false)
    lateinit var schoolName: String

    @DbComment("院系")
    var faculty: String? = null

    @DbComment("专业")
    var major: String? = null

    @DbComment("学历: 专科/本科/硕士/博士")
    var degree: String? = null

    @DbComment("入学年份")
    var startYear: Int? = null

    @DbComment("毕业年份")
    var endYear: Int? = null
}