package com.ruoyi.hospital.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.hospital.domain.TcmConsultation;

/**
 * 问诊Mapper接口
 *
 * @author ruoyi
 */
public interface TcmConsultationMapper
{
    /**
     * 查询问诊列表
     *
     * @param tcmConsultation 问诊
     * @return 问诊集合
     */
    List<TcmConsultation> selectTcmConsultationList(TcmConsultation tcmConsultation);

    /**
     * 查询问诊
     *
     * @param id 问诊主键
     * @return 问诊
     */
    TcmConsultation selectTcmConsultationById(String id);

    TcmConsultation selectTcmConsultationForUpdate(String id);

    /**
     * 按业务问诊编号查询问诊
     *
     * @param consultationId 问诊编号
     * @return 问诊
     */
    TcmConsultation selectTcmConsultationByConsultationId(String consultationId);

    /**
     * 新增问诊
     *
     * @param tcmConsultation 问诊
     * @return 结果
     */
    int insertTcmConsultation(TcmConsultation tcmConsultation);

    /**
     * 修改问诊
     *
     * @param tcmConsultation 问诊
     * @return 结果
     */
    int updateTcmConsultation(TcmConsultation tcmConsultation);

    int updatePdfPayloadIfUnchanged(@Param("consultation") TcmConsultation consultation,
            @Param("expectedPayload") String expectedPayload);

    int reactivateTcmConsultation(TcmConsultation tcmConsultation);

    /**
     * 删除问诊
     *
     * @param id 问诊主键
     * @return 结果
     */
    int deleteTcmConsultationById(String id);
}
