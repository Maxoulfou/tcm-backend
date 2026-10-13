package com.ruoyi.hospital.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.hospital.domain.TcmAppointment;

/**
 * 中医预约 Service接口
 *
 * @author ruoyi
 */
public interface ITcmAppointmentService
{
    /**
     * 查询预约列表
     *
     * @param appointment 预约查询条件
     * @return 预约集合
     */
    List<TcmAppointment> selectTcmAppointmentList(TcmAppointment appointment);

    /**
     * 查询预约详情
     *
     * @param id 预约ID
     * @return 预约信息
     */
    TcmAppointment selectTcmAppointmentById(String id);

    /**
     * 新增预约
     *
     * @param appointment 预约信息
     * @return 影响行数
     */
    int insertTcmAppointment(TcmAppointment appointment);

    /**
     * 修改预约
     *
     * @param appointment 预约信息
     * @return 影响行数
     */
    int updateTcmAppointment(TcmAppointment appointment);

    /** Save an intake form and consume exactly the token presented by its submitter. */
    TcmAppointment completeIntake(String id, String expectedToken, Map<String, Object> formData);

    /**
     * 更新预约状态
     *
     * @param id     预约ID
     * @param status 新状态
     * @return 更新后的预约对象
     */
    TcmAppointment updateStatus(String id, String status);

    /**
     * 检查时间段是否可用
     *
     * @param practitionerId 医师ID
     * @param roomId         诊室ID
     * @param serviceType    服务类型，可为空
     * @param startTime      开始时间
     * @param endTime        结束时间
     * @param excludeId      排除的预约ID（用于编辑时排除自身）
     * @return 检查结果
     */
    Map<String, Object> checkSlot(String practitionerId, String roomId, String serviceType, String startTime, String endTime, String excludeId);

    /**
     * 获取指定日期的可预约时间槽
     *
     * @param date 目标日期（YYYY-MM-DD）
     * @param serviceType 服务类型
     * @param practitionerId 指定医师ID，可为空
     * @param roomId 诊室ID，可为空
     * @param excludeId 编辑时排除的预约ID
     * @return 时间槽列表
     */
    Map<String, Object> getAvailability(String date, String serviceType, String practitionerId, String roomId, String excludeId);

    /**
     * 获取公开预约周视图
     *
     * @param date 目标日期（YYYY-MM-DD）
     * @param serviceType 服务类型
     * @param practitionerId 医师ID
     * @param roomId 诊室ID，可为空
     * @return 一周内每个半小时单元格的安全状态矩阵
     */
    Map<String, Object> getWeeklySchedule(String date, String serviceType, String practitionerId, String roomId);

    /**
     * 根据问诊表单令牌查询预约
     *
     * @param intakeToken 令牌
     * @return 预约信息
     */
    TcmAppointment selectTcmAppointmentByIntakeToken(String intakeToken);
}
