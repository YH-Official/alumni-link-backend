package com.zxce.ai.model.job

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_resume")
class AlkResume : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("简历标题")
    var title: String? = null

    @DbComment("简历文件URL")
    var fileUrl: String? = null

    @DbComment("简历文本内容")
    var content: String? = null

    @DbComment("是否默认简历")
    @JvmField
    var isDefault: Boolean = false
}