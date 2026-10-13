package com.ruoyi.hospital.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.hospital.domain.TcmAppointment;

/**
 * 预约Mapper接口
 *
 * @author ruoyi
 */
public interface TcmAppointmentMapper
{
    /**
     * 查询预约列表
     *
     * @param tcmAppointment 预约
     * @return 预约集合
     */
    List<TcmAppointment> selectTcmAppointmentList(TcmAppointment tcmAppointment);

    /**
     * 查询预约
     *
     * @param id 预约主键
     * @return 预约
     */
    TcmAppointment selectTcmAppointmentById(String id);

    /** Read the current row while serializing appointment edits with notification writes. */
    TcmAppointment selectTcmAppointmentForUpdate(String id);

    TcmAppointment selectTcmAppointmentByManageToken(String token);

    /**
     * 新增预约
     *
     * @param tcmAppointment 预约
     * @return 结果
     */
    int insertTcmAppointment(TcmAppointment tcmAppointment);

    /**
     * 修改预约
     *
     * @param tcmAppointment 预约
     * @return 结果
     */
    int updateTcmAppointment(TcmAppointment tcmAppointment);

    int updateNotificationPayload(@Param("record") TcmAppointment record, @Param("expected") TcmAppointment expected);

    int updateNotificationIntake(@Param("record") TcmAppointment record, @Param("expected") TcmAppointment expected);

    int cancelNotificationAppointment(@Param("record") TcmAppointment record, @Param("expected") TcmAppointment expected);

    /**
     * 删除预约
     *
     * @param id 预约主键
     * @return 结果
     */
    int deleteTcmAppointmentById(String id);

    /**
     * 根据问诊表单令牌查询预约
     */
    TcmAppointment selectTcmAppointmentByIntakeToken(String intakeToken);

    /**
     * 查询重叠的预约（用于时间段检查）
     *
     * @param practitionerId 医师ID
     * @param roomId 房间ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param excludeId 排除的预约ID
     * @return 重叠的预约集合
     */
    List<TcmAppointment> selectOverlappingAppointments(@Param("practitionerId") String practitionerId,
                                                        @Param("roomId") String roomId,
                                                        @Param("startTime") String startTime,
                                                        @Param("endTime") String endTime,
                                                        @Param("excludeId") String excludeId);

    /**
     * 查询指定日期范围内相关医生或房间的预约，用于批量计算可预约时间。
     */
    List<TcmAppointment> selectAppointmentsInRange(@Param("practitionerIds") List<String> practitionerIds,
                                                    @Param("roomIds") List<String> roomIds,
                                                    @Param("startTime") String startTime,
                                                    @Param("endTime") String endTime,
                                                    @Param("excludeId") String excludeId);
}
