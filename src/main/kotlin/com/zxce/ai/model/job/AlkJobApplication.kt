package com.zxce.ai.model.job

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.*

@Entity
@Table(name = "alk_job_application")
class AlkJobApplication : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联alk_job.id")
    @Column(nullable = false)
    lateinit var jobId: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("关联alk_resume.id")
    var resumeId: UUID? = null

    @DbComment("投递状态: 1-投递中 2-已查看 3-面试邀请 4-复试 5-录用 6-拒绝")
    var status: Int = 1

    @DbComment("状态备注")
    var statusMessage: String? = null

    @DbComment("面试时间")
    var interviewTime: Timestamp? = null

    @DbComment("面试地点")
    var interviewLocation: String? = null

    @DbComment("offer薪资(K)")
    var salaryOffered: BigDecimal? = null

    @DbComment("备注")
    var remark: String? = null
}