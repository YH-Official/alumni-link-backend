package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_major")
class AlkMajor : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("专业名称")
    @Column(nullable = false)
    lateinit var name: String

    @DbComment("所属学院ID")
    @Column(nullable = false)
    lateinit var collegeId: UUID

    @DbComment("所属学校ID")
    @Column(nullable = false)
    lateinit var schoolId: UUID
}